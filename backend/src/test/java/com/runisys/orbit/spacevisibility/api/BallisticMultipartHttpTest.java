package com.runisys.orbit.spacevisibility.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 真实嵌入式 Tomcat 在 Controller 选择前拒绝 multipart，MockMvc 不能替代此边界检查。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ballistic.files.max-bytes=16", "space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol"})
class BallisticMultipartHttpTest {
    @TempDir
    static Path root;

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void Servlet实际文件限额与辅助413协议一致() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(new byte[17]) {
            @Override
            public String getFilename() {
                return "large.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = http.postForEntity("/api/v1/ballistic/files", new HttpEntity<>(body, headers), String.class);
        assertEquals(413, response.getStatusCodeValue(), response.getBody());
        assertTrue(response.getBody().contains("FILE_TOO_LARGE"), response.getBody());
    }
}
