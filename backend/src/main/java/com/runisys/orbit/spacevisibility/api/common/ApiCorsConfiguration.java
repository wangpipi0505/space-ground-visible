package com.runisys.orbit.spacevisibility.api.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 独立前端直接连接后端时的可选跨域配置；反向代理同源部署无需开启。
 * 默认空清单不允许跨域。配置逗号分隔的确切前端 Origin，禁止默认放开全部来源。
 * 仅覆盖既有 /api 路径，公开下载文件名响应头，不改变计算接口、文件格式或携带凭据规则。
 */
@Configuration
public class ApiCorsConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public ApiCorsConfiguration(@Value("${space.visibility.web.allowed-origins:}") String origins) {
        this.allowedOrigins = java.util.Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    /** 只在明确配置来源时安装映射，支持 JSON、multipart 和文件下载的实际 HTTP 方法。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length > 0) {
            registry.addMapping("/api/**").allowedOrigins(allowedOrigins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("Content-Type").exposedHeaders("Content-Disposition");
        }
    }
}
