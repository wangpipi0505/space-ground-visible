package com.runisys.orbit.spacevisibility.domain.orbit;

import org.orekit.data.ClasspathCrawler;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.time.TAIUTCDatFilesLoader;
import org.orekit.time.TimeScalesFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orekit默认数据上下文的一次性同步初始化及DE405交付文件校验。
 * 外置目录优先系统属性/环境变量；启动后不随请求重新装载星历，不自动用DE440或解析太阳替代。
 */
public final class OrekitDataInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrekitDataInitializer.class);

    /** 未显式配置时自动探测的内置完整数据目录名（交付包内与 backend 同级位置）。 */
    private static final String BUNDLED_DATA_DIRECTORY = "orekit-data";
    /** WinTool 已确认使用的 JPL DE405 原生文件名格式；不接受 DE440 或 SPICE BSP 作为替代。 */
    private static final String DE405_FILE_NAME_PATTERN = "(?i)^unx[mp]\\d{4}\\.405$";

    private static boolean initialized;
    /** 已挂载的数据根目录，用于在启动校验与运行日志中定位 DE405 文件。 */
    private static File initializedDataDirectory;
    private static De405Metadata de405Metadata;

    private OrekitDataInitializer() {
    }

    /**
     * 初始化 Orekit 数据上下文。优先加载系统属性 orekit.data.path 或环境变量 OREKIT_DATA_PATH
     * 指向的外部目录；未配置时自动探测交付包内置的 orekit-data 目录（工作目录或 JAR 所在目录下），
     * 随后始终加载内置的闰秒文件。
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        File dataDirectory = locateDataDirectory();
        if (dataDirectory != null) {
            DataContext.getDefault().getDataProvidersManager().addProvider(
                    new DirectoryCrawler(dataDirectory));
            LOGGER.info("已挂载 Orekit 数据目录：{}", dataDirectory.getAbsolutePath());
        } else {
            LOGGER.info("未找到完整 Orekit 数据目录，仅使用内置闰秒文件");
        }
        DataContext.getDefault().getDataProvidersManager().addProvider(
                new ClasspathCrawler("orekit-data/tai-utc.dat"));
        TimeScalesFactory.clearUTCTAIOffsetsLoaders();
        TimeScalesFactory.addUTCTAIOffsetsLoader(new TAIUTCDatFilesLoader(
                TAIUTCDatFilesLoader.DEFAULT_SUPPORTED_NAMES,
                DataContext.getDefault().getDataProvidersManager()));
        initialized = true;
        initializedDataDirectory = dataDirectory;
    }

    /**
     * 查找并校验交付包内的 JPL DE405 原生星历文件。
     *
     * <p>本方法刻意不接受当前 orekit-data 自带的 DE440，也不接受 NAIF SPICE 的 {@code .bsp}：
     * WinTool 已明确确认 DE405，因此缺少原生 {@code .405} 文件必须快速失败，而不能悄悄产生
     * 看似可用但口径不一致的太阳方向。</p>
     *
     * @return 已验证文件的只读元数据，供太阳方向加载和启动日志复用
     */
    public static synchronized De405Metadata requireDe405() {
        initialize();
        if (de405Metadata != null) {
            return de405Metadata;
        }
        if (initializedDataDirectory == null) {
            throw new IllegalStateException("未找到 Orekit 数据目录，无法加载 WinTool 要求的 JPL DE405 星历");
        }
        List<File> candidates = new ArrayList<File>();
        collectDe405Files(initializedDataDirectory, candidates);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("未找到 JPL DE405 原生 .405 星历；请将覆盖任务时间的 "
                    + "unxp/lnxp*.405 文件置于 " + initializedDataDirectory.getAbsolutePath());
        }
        Collections.sort(candidates, (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        File file = candidates.get(0);
        de405Metadata = new De405Metadata(file.getName(), file.length(), sha256(file));
        LOGGER.info("已确认 WinTool 太阳星历：JPL DE405，文件={}，大小={}字节，SHA-256前缀={}",
                file.getAbsolutePath(), de405Metadata.getSizeBytes(), de405Metadata.getSha256Prefix());
        return de405Metadata;
    }

    /** 返回当前 DE405 文件名，供 Orekit 的精确文件名加载器使用。 */
    public static String requireDe405FileName() {
        return requireDe405().getFileName();
    }

    private static void collectDe405Files(File directory, List<File> files) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDe405Files(child, files);
            } else if (child.getName().matches(DE405_FILE_NAME_PATTERN)) {
                files.add(child);
            }
        }
    }

    /** 以流式方式计算文件指纹，避免把星历二进制整体读入内存。 */
    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (FileInputStream input = new FileInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder value = new StringBuilder();
            for (byte part : digest.digest()) {
                value.append(String.format("%02X", part & 0xFF));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("无法校验 JPL DE405 文件完整性: " + file.getAbsolutePath(), exception);
        }
    }

    /** JPL DE405 文件的不可变交付元数据。 */
    public static final class De405Metadata {
        private final String fileName;
        private final long sizeBytes;
        private final String sha256;

        private De405Metadata(String fileName, long sizeBytes, String sha256) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        public String getFileName() {
            return fileName;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public String getSha256() {
            return sha256;
        }

        public String getSha256Prefix() {
            return sha256.substring(0, Math.min(12, sha256.length()));
        }
    }

    /**
     * 按优先级定位完整 Orekit 数据目录：显式配置（系统属性/环境变量）、工作目录下的 orekit-data、
     * JAR 所在目录下的 orekit-data。都没有时返回 null。
     */
    private static File locateDataDirectory() {
        String configuredPath = System.getProperty("orekit.data.path", System.getenv("OREKIT_DATA_PATH"));
        File directory = existingDirectory(configuredPath);
        if (directory != null) {
            return directory;
        }
        directory = existingDirectory(BUNDLED_DATA_DIRECTORY);
        if (directory != null) {
            return directory;
        }
        return existingDirectory(new File(applicationDirectory(), BUNDLED_DATA_DIRECTORY).getPath());
    }

    /** 返回存在的目录；路径为空或不是目录时返回 null。 */
    private static File existingDirectory(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File directory = new File(path.trim());
        return directory.isDirectory() ? directory : null;
    }

    /** 定位应用自身（JAR 或类输出目录）所在目录，用于查找随交付包放置的 orekit-data。 */
    private static File applicationDirectory() {
        try {
            CodeSource codeSource = OrekitDataInitializer.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                URL location = codeSource.getLocation();
                if (location != null && "file".equals(location.getProtocol())) {
                    File artifact = new File(location.toURI());
                    File parent = artifact.getParentFile();
                    if (parent != null) {
                        return parent;
                    }
                }
            }
        } catch (Exception ignored) {
            // 定位失败时退回工作目录
        }
        return new File(System.getProperty("user.dir", "."));
    }
}
