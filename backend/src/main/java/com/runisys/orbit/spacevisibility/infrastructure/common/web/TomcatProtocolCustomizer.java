package com.runisys.orbit.spacevisibility.infrastructure.common.web;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.util.StringUtils;

/**
 * 按部署环境显式指定 Tomcat HTTP 连接器实现。
 *
 * <p>未设置协议时绝不调用工厂设置方法，继续使用 Spring Boot 的默认 NIO 连接器；
 * 只有某些本机环境的 NIO 内部回环管道不可用时，才可通过运行参数切换到 NIO2。
 * 此设置只影响 HTTP 事件轮询方式，不影响轨道计算、接口地址或业务数据。</p>
 */
public final class TomcatProtocolCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final String protocol;

    /**
     * @param protocol Tomcat 协议处理器全限定类名；为空时保留 Spring Boot 默认值
     */
    public TomcatProtocolCustomizer(String protocol) {
        this.protocol = protocol;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (StringUtils.hasText(protocol)) {
            String selected = protocol.trim();
            // 保留原 NIO2 参数入口及网络模型，仅注入关闭引用寿命适配；其他协议按原值交给工厂。
            factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol".equals(selected)
                    ? ClosingSafeNio2Protocol.class.getName() : selected);
        }
    }
}
