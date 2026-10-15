package com.runisys.orbit.spacevisibility.infrastructure.ballistic.files;

import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 弹道文件位置解析器：把本地路径、http(s):// 直链、minio:// 地址统一解析为本地文件。
 *
 * <p>远程地址会下载到本地临时文件，以复用现有 Path 解析路径的 Excel 自动识别能力；
 * 临时文件后缀保留来源扩展名（无扩展名默认 .csv），并注册 deleteOnExit。</p>
 *
 * <p>minio:// 地址语法：minio://[accessKey:secretKey@]host[:port]/bucket/object[?secure=true]。
 * userinfo 提供凭证，缺省走匿名访问；默认 http 连接，?secure=true 使用 https；object 名按 URL 解码。</p>
 */
public final class TrajectoryFileResolver {

    private static final int HTTP_CONNECT_TIMEOUT_MS = 15_000;
    private static final int HTTP_READ_TIMEOUT_MS = 60_000;
    private static final int HTTP_MAX_REDIRECTS = 5;

    private TrajectoryFileResolver() {
    }

    /** 本地或网络地址解析为轨迹点；数值与列解析委托领域 Path 入口。 */
    public static List<OrbitCalculator.BallisticPoint> parseBallisticCsv(String location) throws IOException {
        return OrbitCalculator.parseBallisticCsv(resolveToLocalFile(location));
    }

    /** 地址加载与领域解析的组合入口；返回每份文件自己的首点参考历元。 */
    public static OrbitCalculator.ParsedBallisticCsv parseBallisticCsvWithReference(String location) throws IOException {
        return OrbitCalculator.parseBallisticCsvWithReference(resolveToLocalFile(location));
    }

    /**
     * 判断位置字符串是否为远程地址（http://、https://、minio://，前缀大小写不敏感）。
     */
    public static boolean isRemoteLocation(String location) {
        if (location == null) {
            return false;
        }
        String lower = location.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("minio://");
    }

    /**
     * 把位置字符串解析为本地文件路径：本地路径原样返回（不做存在性改写），远程地址下载到临时文件。
     */
    public static Path resolveToLocalFile(String location) throws IOException {
        if (location == null || location.trim().isEmpty()) {
            throw new IllegalArgumentException("弹道文件位置不能为空。");
        }
        String trimmed = location.trim();
        if (!isRemoteLocation(trimmed)) {
            return Paths.get(trimmed);
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("minio://")) {
            return downloadFromMinio(parseMinioLocation(trimmed), null, Long.MAX_VALUE);
        }
        return downloadFromHttp(trimmed, null, Long.MAX_VALUE);
    }

    /**
     * Web 专用资源作用域：远程文件只写指定临时目录，实际复制字节超过 maxBytes 即失败并删除。
     * 本地路径仍原样返回且不删除；调用方必须关闭返回值。旧单参数入口保留系统临时目录与
     * deleteOnExit 语义，因此普通 Java 调用和已有公开签名不变。
     */
    public static ResolvedFile resolveScoped(String location, Path tempDirectory, long maxBytes) throws IOException {
        if (location == null || location.trim().isEmpty() || tempDirectory == null || maxBytes <= 0) {
            throw new IllegalArgumentException("文件位置、临时目录和有效字节上限不能为空");
        }
        String trimmed = location.trim();
        if (!isRemoteLocation(trimmed)) {
            return new ResolvedFile(Paths.get(trimmed), false);
        }
        Path directory = tempDirectory.toRealPath();
        Path downloaded = trimmed.toLowerCase(Locale.ROOT).startsWith("minio://")
                ? downloadFromMinio(parseMinioLocation(trimmed), directory, maxBytes)
                : downloadFromHttp(trimmed, directory, maxBytes);
        return new ResolvedFile(downloaded, true);
    }

    /** 可关闭的实际读取路径。业务输出仍应使用用户原 location，不得用临时路径覆盖逻辑来源。 */
    public static final class ResolvedFile implements AutoCloseable {
        public final Path path;
        private final boolean temporary;

        private ResolvedFile(Path path, boolean temporary) {
            this.path = path;
            this.temporary = temporary;
        }

        @Override
        public void close() throws IOException {
            if (temporary) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path downloadFromHttp(String httpUrl, Path directory, long maxBytes) throws IOException {
        URL current = new URL(httpUrl);
        for (int redirect = 0; ; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("下载弹道文件失败：HTTP " + status + " 重定向缺少 Location 头。");
                }
                if (redirect >= HTTP_MAX_REDIRECTS) {
                    throw new IOException("下载弹道文件失败：重定向次数超过 " + HTTP_MAX_REDIRECTS + " 次。");
                }
                current = new URL(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("下载弹道文件失败：HTTP " + status + "（" + httpUrl + "）。");
            }
            try (InputStream in = connection.getInputStream()) {
                return copyDownloaded(in, suffixFromPath(current.getPath()), directory, maxBytes);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static Path downloadFromMinio(MinioLocation minioLocation, Path directory, long maxBytes) throws IOException {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(minioLocation.host, minioLocation.port, minioLocation.secure);
        if (minioLocation.accessKey != null) {
            builder.credentials(minioLocation.accessKey, minioLocation.secretKey);
        }
        MinioClient client = builder.build();
        InputStream in;
        try {
            in = client.getObject(GetObjectArgs.builder()
                    .bucket(minioLocation.bucket)
                    .object(minioLocation.object)
                    .build());
        } catch (Exception ex) {
            throw new IOException("从 MinIO 拉取弹道文件失败：" + ex.getMessage(), ex);
        }
        try (InputStream input = in) {
            return copyDownloaded(input, suffixFromPath(minioLocation.object), directory, maxBytes);
        }
    }

    /** 网络成功但中途断流/超过上限时也删除已创建实体，避免失败请求积累磁盘碎片。 */
    private static Path copyDownloaded(InputStream input, String suffix, Path directory, long maxBytes) throws IOException {
        Path tempFile = directory == null ? createTempTrajectoryFile(suffix)
                : Files.createTempFile(directory, "alg-trajectory-", suffix);
        boolean complete = false;
        try {
            long size = 0;
            try (OutputStream output = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > maxBytes) {
                        throw new IOException("远程弹道文件超过配置字节上限");
                    }
                    output.write(buffer, 0, count);
                }
            }
            complete = true;
            return tempFile;
        } finally {
            if (!complete) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * 解析 minio:// 地址。包私有以便不连服务做单元测试。
     */
    static MinioLocation parseMinioLocation(String location) {
        URI uri = URI.create(location.trim());
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("非法 minio 地址（缺少主机）：" + location);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        // getPath 已做 URL 解码；去掉前导斜杠后按首个斜杠拆 bucket/object
        String[] parts = path.startsWith("/") ? path.substring(1).split("/", 2) : path.split("/", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("非法 minio 地址（缺少 bucket 或 object）：" + location);
        }
        String accessKey = null;
        String secretKey = null;
        if (uri.getUserInfo() != null) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            accessKey = credentials[0];
            secretKey = credentials.length > 1 ? credentials[1] : "";
        }
        boolean secure = false;
        if (uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "secure".equalsIgnoreCase(kv[0]) && "true".equalsIgnoreCase(kv[1])) {
                    secure = true;
                }
            }
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : (secure ? 443 : 9000);
        return new MinioLocation(uri.getHost(), port, parts[0], parts[1], accessKey, secretKey, secure);
    }

    /** 从路径末段提取文件扩展名作为临时文件后缀；无扩展名默认 .csv。 */
    private static String suffixFromPath(String path) {
        if (path != null) {
            int slash = path.lastIndexOf('/');
            String lastSegment = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = lastSegment.lastIndexOf('.');
            if (dot > 0 && dot < lastSegment.length() - 1) {
                String ext = lastSegment.substring(dot);
                // 仅接受字母数字扩展名，避免注入非法文件名字符
                if (ext.matches("\\.[A-Za-z0-9]{1,10}")) {
                    return ext;
                }
            }
        }
        return ".csv";
    }

    private static Path createTempTrajectoryFile(String suffix) throws IOException {
        Path tempFile = Files.createTempFile("alg-trajectory-", suffix);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    /** minio:// 地址解析结果。 */
    static final class MinioLocation {
        final String host;
        final int port;
        final String bucket;
        final String object;
        final String accessKey;
        final String secretKey;
        final boolean secure;

        MinioLocation(String host, int port, String bucket, String object,
                      String accessKey, String secretKey, boolean secure) {
            this.host = host;
            this.port = port;
            this.bucket = bucket;
            this.object = object;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.secure = secure;
        }
    }
}
