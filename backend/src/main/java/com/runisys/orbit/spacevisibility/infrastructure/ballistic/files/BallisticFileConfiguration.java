package com.runisys.orbit.spacevisibility.infrastructure.ballistic.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.unit.DataSize;

/**
 * 文件部署边界：默认相对于后端工作目录 storage，允许每个目录配置成独立绝对路径。
 * 配置是单一字节限额来源，Servlet 与实际流复制均遵循 maxBytes；请求另留 64 KiB multipart 头开销。
 */
@Configuration
@EnableScheduling
public class BallisticFileConfiguration {
    public final Path uploads;
    public final Path results;
    public final Path temp;
    public final Path metadata;
    /** 单文件上限，默认 64 MiB，必须大于零。 */
    public final long maxBytes;
    /** 最后一次使用后保留毫秒数，默认 24 小时；活动租约不受过期清理影响。 */
    public final long ttlMillis;
    /** 单页返回行数上限，默认 500。 */
    public final int previewMaxRows;

    public BallisticFileConfiguration(
            @Value("${ballistic.files.root:storage}") String root,
            @Value("${ballistic.files.uploads:}") String uploads,
            @Value("${ballistic.files.results:}") String results,
            @Value("${ballistic.files.temp:}") String temp,
            @Value("${ballistic.files.metadata:}") String metadata,
            @Value("${ballistic.files.max-bytes:67108864}") long maxBytes,
            @Value("${ballistic.files.ttl-millis:86400000}") long ttlMillis,
            @Value("${ballistic.files.preview-max-rows:500}") int previewMaxRows) throws IOException {
        if (maxBytes <= 0 || maxBytes > Long.MAX_VALUE - 65536 || ttlMillis <= 0 || previewMaxRows <= 0) {
            throw new IllegalArgumentException("文件字节限额、保留时间和预览上限必须为有效正数");
        }
        Path base = Paths.get(root).toAbsolutePath().normalize();
        this.uploads = directory(base, uploads, "uploads");
        this.results = directory(base, results, "results");
        this.temp = directory(base, temp, "temp");
        this.metadata = directory(base, metadata, "metadata");
        Path[] roots = {this.uploads, this.results, this.temp, this.metadata};
        for (int i = 0; i < roots.length; i++) {
            for (int j = i + 1; j < roots.length; j++) {
                if (roots[i].startsWith(roots[j]) || roots[j].startsWith(roots[i])) {
                    throw new IllegalArgumentException("文件管理目录不能相同或互相嵌套");
                }
            }
        }
        this.maxBytes = maxBytes;
        this.ttlMillis = ttlMillis;
        this.previewMaxRows = previewMaxRows;
    }

    /** 规范化真实目录；符号链接/Junction 的边界以部署启动时的真实根为准。 */
    private static Path directory(Path base, String configured, String fallback) throws IOException {
        Path path = configured.trim().isEmpty() ? base.resolve(fallback) : Paths.get(configured);
        Files.createDirectories(path.toAbsolutePath().normalize());
        return path.toRealPath();
    }

    /** Servlet 临时 multipart 与主动远程下载目录分开，容器负责请求临时文件的关闭和删除。 */
    @Bean
    public MultipartConfigElement multipartConfigElement() throws IOException {
        Path multipart = Files.createDirectories(temp.resolve("multipart"));
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setLocation(multipart.toString());
        factory.setMaxFileSize(DataSize.ofBytes(maxBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxBytes + 65536));
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }
}
