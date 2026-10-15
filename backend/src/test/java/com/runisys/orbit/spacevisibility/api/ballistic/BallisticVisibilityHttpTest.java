package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runisys.orbit.spacevisibility.testsupport.WindowEngineReference;
import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/** 真实随机端口 Tomcat 验证 multipart→serverPath→窗口流式响应，不用 MockMvc 替代网络传输。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")
class BallisticVisibilityHttpTest {
    /** 响应中的九位小数用十进制精确解析，禁止 double 舍入吞掉末位差异。 */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    @TempDir
    static Path root;
    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void 真实HTTP上传计算健康与错误状态() throws Exception {
        assertEquals("OK", http.getForObject("/api/orbit/health", String.class));
        byte[] bytes = Files.readAllBytes(Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv"));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "trajectory.csv"; }
        });
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> upload = http.postForEntity("/api/v1/ballistic/files",
                new HttpEntity<>(form, uploadHeaders), String.class);
        assertEquals(201, upload.getStatusCodeValue());
        ObjectNode request = mapper.createObjectNode();
        request.put("ballisticCsvPath", STRICT_JSON.readTree(upload.getBody()).path("serverPath").asText());
        request.put("startOffsetSec", 1200).put("stepSizeSec", 10).put("numCalculations", 3)
                .put("payloadType", "对空跟踪").put("sunExclusionAngleDeg", 0);
        // 常规HTTP验证窗口全部字段；解释执行专跑 points=1，避免原解析器冷/热JIT高度舍入差掩盖传输断言。
        request.put("trajectoryPointOutput", Integer.getInteger("ballistic.http.points", 0));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = http.postForEntity("/api/orbit/visibility/search",
                new HttpEntity<>(mapper.writeValueAsBytes(request), headers), byte[].class);
        assertEquals(200, response.getStatusCodeValue());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        ((StreamingResponseBody) new WindowEngineReference().search(
                mapper.treeToValue(request, VisibilityRequest.class)).getBody()).writeTo(expected);
        assertEquals(STRICT_JSON.readTree(expected.toByteArray()), STRICT_JSON.readTree(response.getBody()));
        assertTrue(STRICT_JSON.readTree(response.getBody()).path("best").path("visibleArcCount").asInt() > 0);
        request.put("stepSizeSec", 0);
        ResponseEntity<JsonNode> error = http.postForEntity("/api/orbit/visibility/search",
                new HttpEntity<>(mapper.writeValueAsBytes(request), headers), JsonNode.class);
        assertEquals(400, error.getStatusCodeValue());
        assertEquals(1, error.getBody().size());
        assertTrue(error.getBody().has("error"));
        request.put("stepSizeSec", 10).put("ballisticCsvPath", root.resolve("missing.csv").toString());
        error = http.postForEntity("/api/orbit/visibility/search",
                new HttpEntity<>(mapper.writeValueAsBytes(request), headers), JsonNode.class);
        assertEquals(500, error.getStatusCodeValue());
        assertEquals(1, error.getBody().size());
        assertTrue(error.getBody().has("error"));
    }
}
