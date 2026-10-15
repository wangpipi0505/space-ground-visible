package com.runisys.orbit.spacevisibility.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 MVC 配置检查独立部署 JSON 预检及来源隔离，不调用数学主体。 */
@SpringBootTest(properties = "space.visibility.web.allowed-origins=http://127.0.0.1:15130")
@AutoConfigureMockMvc
class ApiCorsConfigurationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void permitsConfiguredFrontendAndDownloadHeader() throws Exception {
        mockMvc.perform(options("/api/orbit/visibility/search")
                .header("Origin", "http://127.0.0.1:15130")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:15130"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Content-Disposition"));
    }

    @Test
    void rejectsUnconfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/orbit/visibility/search")
                .header("Origin", "http://unconfigured.invalid")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
