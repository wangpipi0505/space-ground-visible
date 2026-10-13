package com.tx.alg;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
            return downloadFromMinio(parseMinioLocation(trimmed));
        }
        return downloadFromHttp(trimmed);
    }

    private static Path downloadFromHttp(String httpUrl) throws IOException {
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
            Path tempFile = createTempTrajectoryFile(suffixFromPath(current.getPath()));
            InputStream in = connection.getInputStream();
            try {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
                connection.disconnect();
            }
            return tempFile;
        }
    }

    private static Path downloadFromMinio(MinioLocation minioLocation) throws IOException {
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
        Path tempFile = createTempTrajectoryFile(suffixFromPath(minioLocation.object));
        try {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
        return tempFile;
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
