package com.runisys.orbit.spacevisibility;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

import com.runisys.orbit.spacevisibility.infrastructure.web.TomcatProtocolCustomizer;

@SpringBootApplication
@MapperScan("com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper")
public class SpaceTargetVisibleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaceTargetVisibleApplication.class, args);
    }

    /**
     * 为存在 JDK NIO 回环限制的本机部署提供可选的 NIO2 连接器切换入口。
     * 默认配置为空，故常规部署仍使用 Spring Boot 原有的 Tomcat NIO 连接器。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatProtocolCustomizer(
            @Value("${space.visibility.runtime.tomcat-protocol:}") String tomcatProtocol) {
        return new TomcatProtocolCustomizer(tomcatProtocol);
    }
}
