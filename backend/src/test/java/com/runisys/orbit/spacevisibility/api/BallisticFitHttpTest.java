package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticFitService;
import com.tx.alg.api.TargetTrajectoryFitController;
import com.tx.alg.trajectory.TargetTrajectoryFitter.FitRequest;
import com.tx.alg.trajectory.TargetTrajectoryFitter.FitRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Tomcat 端口验证 T15 拟合入口，原双目标样例与全部 CSV 字节作为数值基准。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")
class BallisticFitHttpTest {
    @TempDir
    static Path root;
    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    BallisticFitService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void 正式健康入口可用且重定时按T17注册() {
        ResponseEntity<String> health = http.getForEntity("/api/trajectory/health", String.class);
        assertEquals(200, health.getStatusCodeValue());
        assertEquals("OK", health.getBody());
        assertEquals(400, http.postForEntity("/api/trajectory/retime", mapper.createObjectNode(), String.class)
                .getStatusCodeValue());
    }

    @Test
    void 缺省输出在管理目录且响应及双目标CSV保持原黄金() throws Exception {
        ObjectNode request = fixture();
        request.remove("outputDir");
        ResponseEntity<JsonNode> response = http.postForEntity("/api/trajectory/fit/package102", request, JsonNode.class);
        assertEquals(200, response.getStatusCodeValue());
        JsonNode result = response.getBody();
        ObjectNode normalized = result.deepCopy();
        for (int i = 0; i < result.path("files").size(); i++) {
            Path csv = Paths.get(result.path("files").get(i).path("csvPath").asText());
            assertTrue(csv.toRealPath().startsWith(root.resolve("results").toRealPath()));
            assertArrayEquals(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/fit/trajectory-" + i + ".csv")),
                    Files.readAllBytes(csv));
            ((ObjectNode) normalized.path("files").get(i)).put("csvPath", "trajectory-" + i + ".csv");
        }
        assertEquals(mapper.readTree(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/fit/result.json"))), normalized);
        // 同请求再走旧 Java 入口；数值、警告、顺序和文件字节全部保留，只映射生成目录及时间戳文件名。
        ObjectNode originalRequest = request.deepCopy();
        originalRequest.put("outputDir", root.resolve("java-reference").toString());
        ResponseEntity<?> legacy = new TargetTrajectoryFitController().fitPackage102(mapper.treeToValue(originalRequest, FitRequest.class));
        assertEquals(200, legacy.getStatusCodeValue());
        assertSameResult(result, mapper.valueToTree(legacy.getBody()));
        assertFrozen(originalRequest, result, 200);

        for (JsonNode file : result.path("files")) {
            String csvPath = file.path("csvPath").asText();
            String autoId = autoRegisteredId(csvPath);
            ObjectNode registration = mapper.createObjectNode().put("csvPath", csvPath)
                    .put("taskName", result.path("taskName").asText()).put("targetId", file.path("targetId").asText());
            ResponseEntity<JsonNode> reference = http.postForEntity("/api/v1/ballistic/files/register-result", registration, JsonNode.class);
            assertEquals(201, reference.getStatusCodeValue());
            assertEquals(autoId, reference.getBody().path("fileId").asText());
            assertEquals(autoId, http.postForObject("/api/v1/ballistic/files/register-result", registration, JsonNode.class)
                    .path("fileId").asText());
            assertArrayEquals(Files.readAllBytes(Paths.get(csvPath)), http.getForObject(
                    "/api/v1/ballistic/files/" + autoId + "/download", byte[].class));
            JsonNode preview = http.getForObject("/api/v1/ballistic/files/" + autoId + "/preview?page=1&limit=3", JsonNode.class);
            assertEquals(21, preview.path("header").size());
            assertEquals(file.path("pointCount").asInt(), preview.path("totalRows").asInt());
            assertEquals(3, preview.path("rows").size());
            java.util.List<String> lines = Files.readAllLines(Paths.get(csvPath), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(mapper.valueToTree(lines.get(0).split(",", -1)), preview.path("header"));
            for (int row = 0; row < 3; row++) {
                assertEquals(mapper.valueToTree(lines.get(row + 1).split(",", -1)), preview.path("rows").get(row));
            }
        }
        ObjectNode window = mapper.createObjectNode().put("ballisticCsvPath", result.path("files").get(0).path("csvPath").asText())
                .put("startOffsetSec", 1200).put("stepSizeSec", 10).put("numCalculations", 1)
                .put("trajectoryPointOutput", 0).put("payloadType", "对空跟踪").put("sunExclusionAngleDeg", 0);
        ResponseEntity<JsonNode> windows = http.postForEntity("/api/orbit/visibility/search", window, JsonNode.class);
        assertEquals(200, windows.getStatusCodeValue());
        assertTrue(windows.getBody().has("allResults"));
    }

    @Test
    void 显式内外目录按原语义且只自动登记管理范围() throws Exception {
        for (String name : new String[]{"results/explicit", "external"}) {
            Path directory = root.resolve(name);
            ObjectNode request = fixture().put("outputDir", directory.toString());
            ResponseEntity<JsonNode> response = http.postForEntity("/api/trajectory/fit/package102", request, JsonNode.class);
            assertEquals(200, response.getStatusCodeValue());
            for (JsonNode file : response.getBody().path("files")) {
                Path csv = Paths.get(file.path("csvPath").asText());
                assertEquals(directory.toRealPath(), csv.getParent().toRealPath());
                ObjectNode registration = mapper.createObjectNode().put("csvPath", csv.toString());
                int expected = name.startsWith("results") ? 201 : 400;
                if (expected == 201) assertNotNull(autoRegisteredId(csv.toString()));
                assertEquals(expected, http.postForEntity("/api/v1/ballistic/files/register-result", registration, JsonNode.class)
                        .getStatusCodeValue());
            }
        }
    }

    @Test
    void 应用编排不修改原请求且普通Java默认仍为点目录() throws Exception {
        FitRequest request = mapper.treeToValue(fixture(), FitRequest.class);
        request.outputDir = ".";
        JsonNode before = mapper.valueToTree(request);
        FitRunResult result = service.fitPackage102(request, false);
        assertEquals(before, mapper.valueToTree(request));
        assertEquals(".", new FitRequest().outputDir);
        assertTrue(Paths.get(result.files.get(0).csvPath).toRealPath().startsWith(root.resolve("results").toRealPath()));
    }

    @Test
    void 非法输入及IO失败保持原400与500错误体() throws Exception {
        ObjectNode invalid = fixture();
        invalid.putArray("package102");
        ObjectNode explicit = invalid.deepCopy().put("outputDir", root.resolve("error-reference").toString());
        JsonNode expected = mapper.valueToTree(new TargetTrajectoryFitController().fitPackage102(
                mapper.treeToValue(explicit, FitRequest.class)).getBody());
        ResponseEntity<JsonNode> response = http.postForEntity("/api/trajectory/fit/package102", invalid, JsonNode.class);
        assertEquals(400, response.getStatusCodeValue());
        assertEquals(expected, response.getBody());
        assertFrozen(explicit, response.getBody(), 400);
        Path blocked = Files.write(root.resolve("cannot-be-directory"), new byte[]{7});
        ObjectNode failure = fixture().put("outputDir", blocked.toString());
        ResponseEntity<?> original = new TargetTrajectoryFitController().fitPackage102(mapper.treeToValue(failure, FitRequest.class));
        response = http.postForEntity("/api/trajectory/fit/package102", failure, JsonNode.class);
        assertEquals(500, original.getStatusCodeValue());
        assertEquals(500, response.getStatusCodeValue());
        assertEquals(mapper.valueToTree(original.getBody()), response.getBody());
        assertFrozen(failure, response.getBody(), 500);
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(blocked));
    }

    /** 在调用登记辅助端点之前读取磁盘登记，证明是计算自动登记而不是测试自行创建的 ID。 */
    private String autoRegisteredId(String csvPath) throws Exception {
        try (Stream<Path> files = Files.list(root.resolve("metadata"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.toString().endsWith(".json")) {
                    JsonNode record = mapper.readTree(Files.readAllBytes(file));
                    if (record.path("reference").path("serverPath").asText().equals(Paths.get(csvPath).toRealPath().toString())) {
                        return record.path("reference").path("fileId").asText();
                    }
                }
            }
        }
        fail("拟合成功后必须自动登记 " + csvPath);
        return null;
    }

    private void assertSameResult(JsonNode actual, JsonNode expected) throws Exception {
        ObjectNode normalized = actual.deepCopy();
        assertEquals(expected.path("files").size(), actual.path("files").size());
        for (int i = 0; i < actual.path("files").size(); i++) {
            String actualPath = actual.path("files").get(i).path("csvPath").asText();
            String expectedPath = expected.path("files").get(i).path("csvPath").asText();
            assertArrayEquals(Files.readAllBytes(Paths.get(expectedPath)), Files.readAllBytes(Paths.get(actualPath)));
            ((ObjectNode) normalized.path("files").get(i)).put("csvPath", expectedPath);
        }
        assertEquals(expected, normalized);
    }

    /** 显式模式才加载只读原版 classes；缺失必须失败，常规检出仅依赖已提交黄金和兼容 Java 入口。 */
    private void assertFrozen(ObjectNode request, JsonNode actual, int status) throws Exception {
        if (!Boolean.getBoolean("ballistic.frozenReference")) return;
        ObjectNode originalRequest = request.deepCopy();
        if (status == 200) originalRequest.put("outputDir", root.resolve("frozen-reference").toString());
        Path classes = Paths.get("../verification/baseline-copies/ballistic/source/target/classes").toRealPath();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.tx.")) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            Class<?> dto = loader.loadClass("com.tx.alg.trajectory.TargetTrajectoryFitter$FitRequest");
            Class<?> controller = loader.loadClass("com.tx.alg.api.TargetTrajectoryFitController");
            ResponseEntity<?> response = (ResponseEntity<?>) controller.getMethod("fitPackage102", dto)
                    .invoke(controller.newInstance(), mapper.treeToValue(originalRequest, dto));
            assertEquals(status, response.getStatusCodeValue());
            JsonNode expected = mapper.valueToTree(response.getBody());
            if (status == 200) assertSameResult(actual, expected);
            else assertEquals(expected, actual);
        }
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readAllBytes(Paths.get(
                "../test-data/reference/ballistic/target_trajectory_fit_package102_default.json")));
    }
}
