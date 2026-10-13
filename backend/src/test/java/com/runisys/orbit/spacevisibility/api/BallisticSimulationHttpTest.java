package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tx.alg.api.TargetTrajectoryFitController;
import com.tx.alg.trajectory.TargetTrajectoryFitter.SimulationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 Tomcat 端口验证正式模拟路由；JSON 只映射生成路径，21 列 CSV 每一个字节都与原黄金对照。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")
@AutoConfigureMockMvc
class BallisticSimulationHttpTest {
    @TempDir
    static Path root;
    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MockMvc mvc;

    @Test
    void MockMvc空请求原400及字段绑定失败沿用既有MVC语义() throws Exception {
        JsonNode error = mapper.readTree(mvc.perform(post("/api/trajectory/simulate").contentType("application/json").content("null"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(mapper.valueToTree(new TargetTrajectoryFitController().simulate(null).getBody()), error);
        // 项目现有 ApiExceptionHandler 的 Exception 兜底将消息转换错误返回 500；T03 未约定它必须是算法 400。
        JsonNode binding = mapper.readTree(mvc.perform(post("/api/trajectory/simulate").contentType("application/json")
                .content("{\"launchPointX\":\"invalid\"}")).andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INTERNAL_ERROR", binding.path("code").asText());
        assertEquals("服务处理失败", binding.path("error").asText());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void 正式模拟注册而健康唯一及重定时按T17注册() {
        assertEquals("OK", http.getForObject("/api/trajectory/health", String.class));
        ResponseEntity<JsonNode> invalid = http.postForEntity("/api/trajectory/simulate", mapper.createObjectNode(), JsonNode.class);
        assertEquals(400, invalid.getStatusCodeValue());
        assertEquals("IllegalArgumentException: launchTimeUtc不能为空。", invalid.getBody().path("error").asText());
        assertEquals(400, http.postForEntity("/api/trajectory/retime", mapper.createObjectNode(), String.class).getStatusCodeValue());
    }

    @Test
    void 省略目录模拟响应及2576点CSV等于原黄金并自动登记下载预览复用() throws Exception {
        ObjectNode request = fixture();
        request.remove("outputDir");
        ResponseEntity<JsonNode> response = simulate(request);
        assertEquals(200, response.getStatusCodeValue());
        JsonNode result = response.getBody();
        Path csv = csv(result);
        assertTrue(csv.toRealPath().startsWith(root.resolve("results").toRealPath()));
        assertEquals(561736, Files.size(csv));
        assertEquals(2576, result.path("files").get(0).path("pointCount").asInt());
        assertArrayEquals(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/simulate/trajectory-0.csv")), Files.readAllBytes(csv));
        ObjectNode normalized = result.deepCopy();
        ((ObjectNode) normalized.path("files").get(0)).put("csvPath", "trajectory-0.csv");
        assertEquals(mapper.readTree(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/simulate/result.json"))), normalized);
        assertOriginal(request, result, 200);

        String id = autoRegisteredId(csv);
        ObjectNode register = mapper.createObjectNode().put("csvPath", csv.toString()).put("taskName", result.path("taskName").asText())
                .put("targetId", result.path("files").get(0).path("targetId").asText());
        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> registration = http.postForEntity("/api/v1/ballistic/files/register-result", register, JsonNode.class);
            assertEquals(201, registration.getStatusCodeValue());
            assertEquals(id, registration.getBody().path("fileId").asText());
            assertEquals(csv.toRealPath().toString(), registration.getBody().path("serverPath").asText());
        }
        assertArrayEquals(Files.readAllBytes(csv), http.getForObject("/api/v1/ballistic/files/" + id + "/download", byte[].class));
        JsonNode preview = http.getForObject("/api/v1/ballistic/files/" + id + "/preview?page=1&limit=3", JsonNode.class);
        assertEquals(21, preview.path("header").size());
        assertEquals(2576, preview.path("totalRows").asInt());
        java.util.List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(mapper.valueToTree(lines.get(0).split(",", -1)), preview.path("header"));
        for (int i = 0; i < 3; i++) assertEquals(mapper.valueToTree(lines.get(i + 1).split(",", -1)), preview.path("rows").get(i));
        ObjectNode window = mapper.createObjectNode().put("ballisticCsvPath", csv.toString()).put("startOffsetSec", 0)
                .put("stepSizeSec", 10).put("numCalculations", 1).put("trajectoryPointOutput", 0)
                .put("payloadType", "对空跟踪").put("sunExclusionAngleDeg", 0);
        // 原默认卫星历元为 7 月 1 日，而模拟黄金发射在 6 月 4 日；显式同日历元避免无关的 27 天 J2 回推。
        window.putArray("satellites").addObject().put("satelliteId", "SIM-REUSE-SAT")
                .put("epochUtc", request.path("launchTimeUtc").asText());
        ResponseEntity<JsonNode> windows = http.postForEntity("/api/orbit/visibility/search", window, JsonNode.class);
        assertEquals(200, windows.getStatusCodeValue());
        assertEquals(1, windows.getBody().path("allResults").size());
    }

    @Test
    void 显式内外目录保持原输出及辅助登记范围() throws Exception {
        for (String name : new String[]{"results/explicit", "external"}) {
            Path directory = root.resolve(name);
            ObjectNode request = fixture().put("outputDir", directory.toString());
            ResponseEntity<JsonNode> response = simulate(request);
            assertEquals(200, response.getStatusCodeValue());
            Path csv = csv(response.getBody());
            assertEquals(directory.toRealPath(), csv.getParent().toRealPath());
            int status = name.startsWith("results") ? 201 : 400;
            if (status == 201) assertNotNull(autoRegisteredId(csv));
            assertEquals(status, http.postForEntity("/api/v1/ballistic/files/register-result",
                    mapper.createObjectNode().put("csvPath", csv.toString()), JsonNode.class).getStatusCodeValue());
            assertOriginal(request, response.getBody(), 200);
        }
    }

    @Test
    void 坐标单位时间别名输出间隔和事件旧字段逐项对照原入口() throws Exception {
        // 坐标标签及单位只使用原解析器已有支持项；旧动力学字段和 phaseEvents 当前仅保留元数据语义。
        ObjectNode kilometres = fixture();
        for (String point : new String[]{"launchPoint", "impactPoint"}) {
            kilometres.put(point + "Unit", "km");
            for (String axis : new String[]{"X", "Y", "Z"}) kilometres.put(point + axis, kilometres.path(point + axis).asDouble() / 1000.0);
        }
        ObjectNode ecef = fixture().put("launchPointCoordFrame", "ECEF").put("impactPointCoordFrame", "ECEF");
        ObjectNode geodetic = fixture().put("launchPointCoordFrame", "WGS84").put("impactPointCoordFrame", "WGS84")
                .put("launchPointX", 120.0).put("launchPointY", 30.0).put("launchPointZ", 0.0)
                .put("impactPointX", 121.0).put("impactPointY", 31.0).put("impactPointZ", 0.0);
        geodetic.remove("impactTimeUtc");
        ObjectNode alias = fixture().put("impactTime", fixture().path("impactTimeUtc").asText()).put("outputPointDurationSec", 0)
                .put("outputStepSec", 2.5).put("taskStartTime", "2026-06-04 03:59:59.000")
                .put("taskEndTime", "2026-06-04 04:43:00.000").put("integrationStepSec", 9)
                .put("initialSpeedMs", 7).put("boostAccelerationMs2", 100).put("flightPhaseCode", "0x02");
        alias.remove("impactTimeUtc");
        alias.putArray("phaseEvents").addObject().put("phaseType", "BOOST").put("eventTime", "ignored")
                .put("accelerationMs2", 30).put("deltaVms", 20).put("directionMode", "CUSTOM");
        ObjectNode priority = fixture().put("impactTime", "2026-06-04 03:00:00.000");
        for (ObjectNode request : new ObjectNode[]{kilometres, ecef, geodetic, alias, priority}) {
            request.remove("outputDir");
            ResponseEntity<JsonNode> response = simulate(request);
            assertEquals(200, response.getStatusCodeValue(), response.getBody().toString());
            assertOriginal(request, response.getBody(), 200);
        }
    }

    @Test
    void 原坐标标签单位别名及UTC斜杠格式均由原解析器处理() throws Exception {
        for (String frame : new String[]{"ECI", "ECI_J2000", "ITRF", "WGS84_ECEF", "LLA", "LLH", "BLH", "GEODETIC", "WGS84_LLA"}) {
            ObjectNode request = fixture().put("launchPointCoordFrame", frame).put("impactPointCoordFrame", frame)
                    .put("outputPointDurationSec", 60).put("launchTimeUtc", "2026/06/04 04:00:00.000")
                    .put("impactTimeUtc", "2026/06/04 04:42:55.000");
            if (frame.contains("LL") || frame.equals("BLH") || frame.equals("GEODETIC")) {
                request.put("launchPointX", 111.57).put("launchPointY", 38.7).put("launchPointZ", 1600)
                        .put("impactPointX", 86.1746).put("impactPointY", 41.725).put("impactPointZ", 1000);
            }
            request.remove("outputDir");
            ResponseEntity<JsonNode> response = simulate(request);
            assertEquals(200, response.getStatusCodeValue(), response.getBody().toString());
            assertOriginal(request, response.getBody(), 200);
        }
        for (String unit : new String[]{"meter", "meters", "kilometer", "kilometers", "0.1m", "dm", "package102", "102", "0.25"}) {
            ObjectNode request = fixture().put("outputPointDurationSec", 60);
            double factor = unit.startsWith("kilo") ? 1000 : unit.equals("meter") || unit.equals("meters") ? 1
                    : unit.equals("0.25") ? 0.25 : 0.1;
            for (String point : new String[]{"launchPoint", "impactPoint"}) {
                request.put(point + "Unit", unit);
                for (String axis : new String[]{"X", "Y", "Z"}) request.put(point + axis, request.path(point + axis).asDouble() / factor);
            }
            request.putNull("phaseEvents");
            request.remove("outputDir");
            ResponseEntity<JsonNode> response = simulate(request);
            assertEquals(200, response.getStatusCodeValue(), response.getBody().toString());
            assertOriginal(request, response.getBody(), 200);
        }
    }

    @Test
    void 非法输入IO失败和组合输入保持原错误状态文本及先后() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{7});
        ObjectNode missingLaunch = fixture().put("launchTimeUtc", " ").put("outputDir", "invalid\u0000directory");
        ObjectNode tinyStep = missingLaunch.deepCopy().put("outputPointDurationSec", 0.0001);
        ObjectNode invalidTime = fixture().put("launchTimeUtc", "invalid-time").put("outputDir", blocked.toString());
        ObjectNode io = fixture().put("outputDir", blocked.toString());
        ObjectNode radius = fixture().put("launchPointX", 0).put("launchPointY", 0).put("launchPointZ", 0);
        radius.remove("outputDir");
        ObjectNode impact = fixture().put("impactTimeUtc", "2026-06-04 03:59:59.000");
        impact.remove("outputDir");
        for (ObjectNode request : new ObjectNode[]{tinyStep, missingLaunch, invalidTime, io, radius, impact}) {
            ResponseEntity<JsonNode> response = simulate(request);
            int expected = request == invalidTime || request == io ? 500 : 400;
            assertEquals(expected, response.getStatusCodeValue());
            assertOriginal(request, response.getBody(), expected);
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(blocked));
    }

    private ResponseEntity<JsonNode> simulate(ObjectNode request) {
        return http.postForEntity("/api/trajectory/simulate", request, JsonNode.class);
    }

    private Path csv(JsonNode result) {
        return Paths.get(result.path("files").get(0).path("csvPath").asText());
    }

    /** 计算后直接核对持久登记，辅助 register-result 不得成为测试中登记的来源。 */
    private String autoRegisteredId(Path csv) throws Exception {
        try (Stream<Path> records = Files.list(root.resolve("metadata"))) {
            for (Path path : (Iterable<Path>) records::iterator) {
                if (path.toString().endsWith(".json")) {
                    JsonNode record = mapper.readTree(Files.readAllBytes(path));
                    if (csv.toRealPath().toString().equals(record.path("reference").path("serverPath").asText())) {
                        return record.path("reference").path("fileId").asText();
                    }
                }
            }
        }
        fail("模拟必须自动登记 " + csv);
        return null;
    }

    /** 默认模式直接核对原 Java 兼容入口，显式冻结模式再追加隔离加载原版实现的独立数值断言。 */
    private void assertOriginal(ObjectNode request, JsonNode actual, int status) throws Exception {
        ObjectNode reference = request.deepCopy();
        if (status == 200 || !reference.has("outputDir")) reference.put("outputDir", root.resolve("java-reference").toString());
        ResponseEntity<?> legacy = new TargetTrajectoryFitController().simulate(mapper.treeToValue(reference, SimulationRequest.class));
        assertEquals(status, legacy.getStatusCodeValue());
        assertSameResult(actual, mapper.valueToTree(legacy.getBody()), status);
        if (!Boolean.getBoolean("ballistic.frozenReference")) return;
        if (status == 200) reference.put("outputDir", root.resolve("frozen-reference").toString());
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
            Class<?> dto = loader.loadClass("com.tx.alg.trajectory.TargetTrajectoryFitter$SimulationRequest");
            Class<?> controller = loader.loadClass("com.tx.alg.api.TargetTrajectoryFitController");
            ResponseEntity<?> frozen = (ResponseEntity<?>) controller.getMethod("simulate", dto)
                    .invoke(controller.newInstance(), mapper.treeToValue(reference, dto));
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

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readAllBytes(Paths.get("../test-data/reference/ballistic/target_trajectory_simulate_default.json")));
    }
}
