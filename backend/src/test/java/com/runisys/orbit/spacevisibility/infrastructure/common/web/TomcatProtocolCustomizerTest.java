package com.runisys.orbit.spacevisibility.infrastructure.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 验证本机部署可按需切换 Tomcat 连接器，而默认配置不改变原有连接器。 */
class TomcatProtocolCustomizerTest {

    private static final String CUSTOMIZER_CLASS =
            "com.runisys.orbit.spacevisibility.infrastructure.common.web.TomcatProtocolCustomizer";
    private static final String NIO2_PROTOCOL = "org.apache.coyote.http11.Http11Nio2Protocol";

    @Test
    void appliesExplicitTomcatProtocol() {
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer = createCustomizer(NIO2_PROTOCOL);
        RecordingTomcatFactory factory = new RecordingTomcatFactory();

        customizer.customize(factory);

        assertThat(factory.protocol).isEqualTo(ClosingSafeNio2Protocol.class.getName());
    }

    @Test
    void leavesProtocolUntouchedWhenNoProtocolIsConfigured() {
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer = createCustomizer("  ");
        RecordingTomcatFactory factory = new RecordingTomcatFactory();

        customizer.customize(factory);

        assertThat(factory.protocol).isNull();
    }

    @SuppressWarnings("unchecked")
    private WebServerFactoryCustomizer<TomcatServletWebServerFactory> createCustomizer(String protocol) {
        Class<?> customizerType = assertDoesNotThrow(() -> Class.forName(CUSTOMIZER_CLASS));
        Object customizer = assertDoesNotThrow(() -> customizerType.getConstructor(String.class).newInstance(protocol));
        assertThat(customizer).isInstanceOf(WebServerFactoryCustomizer.class);
        return (WebServerFactoryCustomizer<TomcatServletWebServerFactory>) customizer;
    }

    /** 捕获工厂接收的协议值，避免启动实际 Web 容器。 */
    private static final class RecordingTomcatFactory extends TomcatServletWebServerFactory {
        private String protocol;

        @Override
        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }
    }
}
