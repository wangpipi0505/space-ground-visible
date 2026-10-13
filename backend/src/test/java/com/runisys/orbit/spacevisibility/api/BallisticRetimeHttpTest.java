package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tx.alg.api.TargetTrajectoryFitController;
import com.tx.alg.trajectory.TargetTrajectoryFitter.RetimeTrajectoryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 正式重定时的真实 HTTP 契约；原黄金与变体仅映射 csvPath，CSV 全字节保持，无数值容差。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")
@AutoConfigureMockMvc
class BallisticRetimeHttpTest {
    @TempDir static Path root;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void 正式重定时注册并沿用原非法请求错误() {
        assertEquals("OK", http.getForObject("/api/trajectory/health", String.class));
        ResponseEntity<JsonNode> response = retime(mapper.createObjectNode());
        assertEquals(400, response.getStatusCodeValue());
        assertEquals("IllegalArgumentException: trajectoryCsvPath不能为空。", response.getBody().path("error").asText());
    }

    @Test
    void 原默认黄金自动登记下载预览及结果再次窗口输入() throws Exception {
        ObjectNode request = fixture(source());
        ResponseEntity<JsonNode> response = retime(request);
        assertEquals(200, response.getStatusCodeValue());
        JsonNode result = response.getBody();
        Path csv = csv(result);
        assertTrue(csv.toRealPath().startsWith(root.resolve("results").toRealPath()));
        assertArrayEquals(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/retime/trajectory-0.csv")), Files.readAllBytes(csv));
        ObjectNode normalized = result.deepCopy();
        ((ObjectNode) normalized.path("files").get(0)).put("csvPath", "trajectory-0.csv");
        assertEquals(mapper.readTree(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/retime/result.json"))), normalized);
        assertOriginal(request, result, 200);
        String id = autoRegisteredId(csv);
        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> registered = http.postForEntity("/api/v1/ballistic/files/register-result",
                    mapper.createObjectNode().put("csvPath", csv.toString()).put("taskName", "T04-retime").put("targetId", "RETIMED-TGT"), JsonNode.class);
            assertEquals(201, registered.getStatusCodeValue());
            assertEquals(id, registered.getBody().path("fileId").asText());
        }
        assertArrayEquals(Files.readAllBytes(csv), http.getForObject("/api/v1/ballistic/files/" + id + "/download", byte[].class));
        JsonNode preview = http.getForObject("/api/v1/ballistic/files/" + id + "/preview?page=1&limit=3", JsonNode.class);
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(mapper.valueToTree(lines.get(0).split(",", -1)), preview.path("header"));
        assertEquals(result.path("files").get(0).path("pointCount").asInt(), preview.path("totalRows").asInt());
        for (int i = 0; i < 3; i++) assertEquals(mapper.valueToTree(lines.get(i + 1).split(",", -1)), preview.path("rows").get(i));
        ObjectNode window = mapper.createObjectNode().put("ballisticCsvPath", csv.toString()).put("startOffsetSec", 0)
                .put("stepSizeSec", 10).put("numCalculations", 1).put("trajectoryPointOutput", 0)
                .put("payloadType", "对空跟踪").put("sunExclusionAngleDeg", 0);
        // 原数值模型按卫星历元传播；本测试明确新发射同日历元，只验证文件复用而不要求存在可见弧段。
        window.putArray("satellites").addObject().put("satelliteId", "RETIME-REUSE-SAT")
                .put("epochUtc", request.path("launchTimeUtc").asText());
        ResponseEntity<JsonNode> windows = http.postForEntity("/api/orbit/visibility/search", window, JsonNode.class);
        assertEquals(200, windows.getStatusCodeValue());
        assertEquals(1, windows.getBody().path("allResults").size());
    }

    @Test
    void 上传及已模拟登记的服务路径可直接重定时() throws Exception {
        LinkedMultiValueMap<String, Object> upload = new LinkedMultiValueMap<>();
        upload.add("file", new FileSystemResource(source().toFile()));
        ResponseEntity<JsonNode> uploaded = http.postForEntity("/api/v1/ballistic/files", upload, JsonNode.class);
        assertEquals(201, uploaded.getStatusCodeValue());
        ObjectNode request = fixture(Paths.get(uploaded.getBody().path("serverPath").asText()));
        request.put("trajectoryCsvPath", "  " + request.path("trajectoryCsvPath").asText() + "  ");
        ResponseEntity<JsonNode> retimed = retime(request);
        assertEquals(200, retimed.getStatusCodeValue());
        assertOriginal(request, retimed.getBody(), 200);
        ObjectNode simulation = (ObjectNode) mapper.readTree(Files.readAllBytes(Paths.get("../test-data/reference/ballistic/target_trajectory_simulate_default.json")));
        simulation.remove("outputDir");
        ResponseEntity<JsonNode> generated = http.postForEntity("/api/trajectory/simulate", simulation, JsonNode.class);
        assertEquals(200, generated.getStatusCodeValue());
        assertNotNull(autoRegisteredId(csv(generated.getBody())));
        request = fixture(csv(generated.getBody()));
        retimed = retime(request);
        assertEquals(200, retimed.getStatusCodeValue());
        assertOriginal(request, retimed.getBody(), 200);
    }

    @Test
    void 未知列标志已有间隔缺失dt空行及跨日非整秒保持原处理() throws Exception {
        List<String> lines = Files.readAllLines(source(), StandardCharsets.UTF_8);
        Path variant = root.resolve("variant.csv");
        java.util.ArrayList<String> text = new java.util.ArrayList<>();
        text.add(lines.get(0).replace(",dt,", ",unknown,") + ",timeIntervalMs,extra");
        String[] first = lines.get(1).split(",", -1);
        first[10] = "unchanged";
        first[20] = "1";
        text.add(String.join(",", first) + ",999.125,保留A");
        text.add("");
        String[] second = lines.get(2).split(",", -1);
        second[5] = "01.250";
        second[20] = "-1";
        text.add(String.join(",", second) + ",555.250,保留B");
        String[] third = lines.get(3).split(",", -1);
        third[5] = "02.875";
        text.add(String.join(",", third) + ",777.875,保留C");
        Files.write(variant, text, StandardCharsets.UTF_8);
        ObjectNode request = fixture(variant).put("launchTimeUtc", "2026-07-02 23:59:59.375");
        ResponseEntity<JsonNode> response = retime(request);
        assertEquals(200, response.getStatusCodeValue());
        assertOriginal(request, response.getBody(), 200);
        List<String> output = Files.readAllLines(csv(response.getBody()), StandardCharsets.UTF_8);
        assertEquals(4, output.size());
        String[] header = output.get(0).split(",", -1);
        assertEquals(23, header.length);
        assertEquals("00.625", output.get(2).split(",", -1)[5]);
        assertEquals("1250.000", output.get(2).split(",", -1)[21]);
        assertEquals("1625.000", output.get(3).split(",", -1)[21]);
        for (int row = 1; row < 4; row++) {
            String[] original = text.get(row == 1 ? 1 : row + 1).split(",", -1);
            String[] actual = output.get(row).split(",", -1);
            for (int col = 0; col < header.length; col++) {
                if (header[col].startsWith("ECEF_") || header[col].equals("isOriginal") || header[col].equals("unknown") || header[col].equals("extra")) {
                    assertEquals(original[col], actual[col], header[col]);
                }
            }
        }
    }

    @Test
    void 原错误及组合顺序严格对照旧入口() throws Exception {
        List<String> lines = Files.readAllLines(source(), StandardCharsets.UTF_8);
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{7});
        Path empty = Files.write(root.resolve("empty.csv"), new byte[0]);
        Path headerOnly = Files.write(root.resolve("header-only.csv"), java.util.Collections.singletonList(lines.get(0)), StandardCharsets.UTF_8);
        Path wrong = Files.write(root.resolve("wrong.csv"), java.util.Arrays.asList(lines.get(0).replace("ECEF_X", "ECEF_X(m)"), lines.get(1)), StandardCharsets.UTF_8);
        Path noYear = Files.write(root.resolve("no-year.csv"), java.util.Arrays.asList(lines.get(0).replace("YYYY", "year"), lines.get(1)), StandardCharsets.UTF_8);
        Path shortRow = Files.write(root.resolve("short.csv"), java.util.Arrays.asList(lines.get(0), "2026,7"), StandardCharsets.UTF_8);
        Path wrongTime = Files.write(root.resolve("wrong-time.csv"), java.util.Arrays.asList(lines.get(0), lines.get(1).replaceFirst("2026", "year")), StandardCharsets.UTF_8);
        for (ObjectNode request : new ObjectNode[]{
                fixture(source()).put("trajectoryCsvPath", " ").put("launchTimeUtc", " ").put("outputDir", "bad\u0000dir"),
                fixture(source()).put("launchTimeUtc", " ").put("trajectoryCsvPath", "bad\u0000source"),
                fixture(root.resolve("missing.csv")).put("outputDir", "bad\u0000dir"),
                fixture(source()).put("trajectoryCsvPath", "bad\u0000source"),
                fixture(empty), fixture(headerOnly), fixture(wrong), fixture(noYear), fixture(shortRow), fixture(wrongTime),
                fixture(wrong).put("launchTimeUtc", "invalid"), fixture(source()).put("launchTimeUtc", "invalid"),
                fixture(wrong).put("launchTimeUtc", "invalid").put("outputDir", "bad\u0000dir"),
                fixture(wrong).put("launchTimeUtc", "invalid").put("outputDir", blocked.toString()),
                fixture(empty).put("launchTimeUtc", "invalid").put("outputDir", blocked.toString())}) {
            ResponseEntity<JsonNode> response = retime(request);
            assertTrue(response.getStatusCodeValue() == 400 || response.getStatusCodeValue() == 500);
            assertOriginal(request, response.getBody(), response.getStatusCodeValue());
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(blocked));
    }

    @Test
    void 显式内外输出目录保持原路径及辅助登记范围() throws Exception {
        for (String name : new String[]{"results/explicit", "external"}) {
            Path directory = root.resolve(name);
            ObjectNode request = fixture(source()).put("outputDir", directory.toString());
            ResponseEntity<JsonNode> response = retime(request);
            assertEquals(200, response.getStatusCodeValue());
            assertEquals(directory.toRealPath(), csv(response.getBody()).getParent().toRealPath());
            if (name.startsWith("results")) assertNotNull(autoRegisteredId(csv(response.getBody())));
            assertEquals(name.startsWith("results") ? 201 : 400, http.postForEntity("/api/v1/ballistic/files/register-result",
                    mapper.createObjectNode().put("csvPath", csv(response.getBody()).toString()), JsonNode.class).getStatusCodeValue());
            assertOriginal(request, response.getBody(), 200);
        }
    }

    @Test
    void MockMvc空请求与不合法绑定沿用实际MVC规则() throws Exception {
        JsonNode error = mapper.readTree(mvc.perform(post("/api/trajectory/retime").contentType("application/json").content("null"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(mapper.valueToTree(new TargetTrajectoryFitController().retime(null).getBody()), error);
        JsonNode binding = mapper.readTree(mvc.perform(post("/api/trajectory/retime").contentType("application/json")
                .content("{\"trajectoryCsvPath\":{\"invalid\":1}}"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INTERNAL_ERROR", binding.path("code").asText());
    }

    private ResponseEntity<JsonNode> retime(ObjectNode request) { return http.postForEntity("/api/trajectory/retime", request, JsonNode.class); }
    private Path source() throws Exception { return Paths.get("../test-data/baseline/ballistic/simulate/trajectory-0.csv").toRealPath(); }
    private Path csv(JsonNode result) { return Paths.get(result.path("files").get(0).path("csvPath").asText()); }
    private ObjectNode fixture(Path path) {
        return mapper.createObjectNode().put("taskName", "T04-retime").put("targetId", "RETIMED-TGT")
                .put("trajectoryCsvPath", path.toString()).put("launchTimeUtc", "2026-07-02 07:57:00.000");
    }

    /** 首次辅助登记之前读持久元数据，防止登记接口掩盖遗漏的自动登记。 */
    private String autoRegisteredId(Path csv) throws Exception {
        try (Stream<Path> records = Files.list(root.resolve("metadata"))) {
            for (Path path : (Iterable<Path>) records::iterator) {
                if (path.toString().endsWith(".json")) {
                    JsonNode record = mapper.readTree(Files.readAllBytes(path));
                    if (csv.toRealPath().toString().equals(record.path("reference").path("serverPath").asText())) return record.path("reference").path("fileId").asText();
                }
            }
        }
        fail("重定时必须自动登记 " + csv);
        return null;
    }

    /** 常规执行不依赖冻结 classes；显式 frozenReference=true 时缺失副本立即失败，不偷偷退回同实现。 */
    private void assertOriginal(ObjectNode request, JsonNode actual, int status) throws Exception {
        ObjectNode reference = request.deepCopy();
        if (status == 200 || !reference.has("outputDir")) reference.put("outputDir", root.resolve("java-reference").toString());
        ResponseEntity<?> legacy = new TargetTrajectoryFitController().retime(mapper.treeToValue(reference, RetimeTrajectoryRequest.class));
        assertEquals(status, legacy.getStatusCodeValue());
        assertSameResult(actual, mapper.valueToTree(legacy.getBody()), status);
        if (!Boolean.getBoolean("ballistic.frozenReference")) return;
        if (status == 200) reference.put("outputDir", root.resolve("frozen-reference").toString());
        Path classes = Paths.get("../verification/baseline-copies/ballistic/source/target/classes").toRealPath();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.tx.")) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            Class<?> dto = loader.loadClass("com.tx.alg.trajectory.TargetTrajectoryFitter$RetimeTrajectoryRequest");
            Class<?> controller = loader.loadClass("com.tx.alg.api.TargetTrajectoryFitController");
            ResponseEntity<?> frozen = (ResponseEntity<?>) controller.getMethod("retime", dto).invoke(controller.newInstance(), mapper.treeToValue(reference, dto));
            assertEquals(status, frozen.getStatusCodeValue());
            assertSameResult(actual, mapper.valueToTree(frozen.getBody()), status);
        }
    }

    private void assertSameResult(JsonNode actual, JsonNode expected, int status) throws Exception {
        ObjectNode normalized = actual.deepCopy();
        if (status == 200) {
            assertArrayEquals(Files.readAllBytes(csv(expected)), Files.readAllBytes(csv(actual)));
            ((ObjectNode) normalized.path("files").get(0)).put("csvPath", expected.path("files").get(0).path("csvPath").asText());
        }
        assertEquals(expected, normalized);
    }
}
