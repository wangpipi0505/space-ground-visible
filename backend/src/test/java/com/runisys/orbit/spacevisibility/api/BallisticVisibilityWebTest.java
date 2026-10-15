package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.runisys.orbit.spacevisibility.testsupport.WindowEngineReference;
import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T14 正式扫描范围内的异步流式路由；真实文件上传后直接将 serverPath 交给计算入口。 */
@SpringBootTest
@AutoConfigureMockMvc
class BallisticVisibilityWebTest {
    /** 响应中的九位小数用十进制精确解析，禁止 double 舍入吞掉末位差异。 */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    @TempDir
    static Path root;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
    }

    @Test
    void 正式健康路由返回原纯文本() throws Exception {
        mvc.perform(get("/api/orbit/health")).andExpect(status().isOk()).andExpect(content().string("OK"));
    }

    @Test
    void 上传标准CSV后异步计算与相同路径的旧Java调用严格相等() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv"));
        JsonNode ref = STRICT_JSON.readTree(mvc.perform(multipart("/api/v1/ballistic/files")
                .file(new MockMultipartFile("file", "标准弹道.csv", "text/csv", bytes)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        ObjectNode request = mapper.createObjectNode();
        request.put("ballisticCsvPath", "  " + ref.path("serverPath").asText() + "  ");
        request.put("numCalculations", 3).put("startOffsetSec", 1200).put("stepSizeSec", 10);
        assertCompatible(request, 200, search(request));
    }

    /** 冻结的六个 Java 正窗口案例使用内部简单锥覆盖；HTTP 仅使用 DTO 本来可表达的对空跟踪。 */
    @ParameterizedTest
    @CsvSource({"TWO_BODY,0", "TWO_BODY,1", "J2_SECULAR,0", "J2_SECULAR,1", "J2_NUMERICAL,0", "J2_NUMERICAL,1"})
    void 两目标两星每星两载荷三偏移与冻结原Controller逐字段严格相等(String model, int points) throws Exception {
        ObjectNode request = multiRequest();
        request.put("orbitModel", model).put("trajectoryPointOutput", points);
        for (JsonNode satellite : request.path("satellites")) {
            for (JsonNode payload : satellite.path("payloads")) {
                ((ObjectNode) payload).put("payloadType", "对空跟踪");
            }
        }
        JsonNode actual = search(request);
        assertCompatible(request, 200, actual);
        assertEquals(3, actual.path("allResults").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1200 + i * 10, actual.path("allResults").get(i).path("offsetSecond").asInt());
        }
        // 在旧 DTO 允许的载荷下实测有稳定弧段，确保点开关与统计的断言没有因全空窗口而失效。
        assertTrue(actual.path("best").path("visibleArcCount").asInt() > 0);
        int pointCount = countTrajectoryPoints(actual);
        assertEquals(points == 1, pointCount > 0);
    }

    @Test
    void 原HTTP对地黄金全空与并列首偏移仍严格保持() throws Exception {
        ObjectNode request = multiRequest();
        request.put("orbitModel", "J2_NUMERICAL").put("trajectoryPointOutput", 1);
        JsonNode actual = search(request);
        assertCompatible(request, 200, actual);
        assertEquals(0, actual.path("best").path("visibleArcCount").asInt());
        assertEquals(1200, actual.path("best").path("offsetSecond").asInt());
        JsonNode normalized = actual.deepCopy();
        String source = request.path("ballistics").get(0).path("csvPath").asText();
        restoreFixturePath(normalized.path("best"), source);
        for (JsonNode result : normalized.path("allResults")) restoreFixturePath(result, source);
        assertEquals(STRICT_JSON.readTree(Files.readAllBytes(Paths.get(
                "../test-data/baseline/ballistic/visibility-http-ground/result.json"))), normalized);
    }

    /** 仅将本案例目标的精确 csvPath 还原成已提交黄金的工作根占位；所有其它字符串和数字保持原样。 */
    private void restoreFixturePath(JsonNode result, String source) {
        for (JsonNode target : result.path("targets")) {
            assertEquals(source, target.path("csvPath").asText());
            ((ObjectNode) target).put("csvPath", "<WORKSPACE>\\test-data\\reference\\ballistic\\trajectory-0.1s-standard.csv");
        }
    }

    @Test
    void 远程多源独立历元别名原URI及成功失败作用域清理() throws Exception {
        byte[] first = Files.readAllBytes(Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv"));
        // 每行 UTC 日期只改日字段；保留原所有位置速度与其它列，构造源历元相差一天的独立文件。
        byte[] second = new String(first, StandardCharsets.UTF_8).replace("2026,07,01,", "2026,07,02,")
                .replace("2026,7,1,", "2026,7,2,").getBytes(StandardCharsets.UTF_8);
        assertFalse(java.util.Arrays.equals(first, second), "源夹具必须实际移动日期");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/first.csv", first, 200);
        serve(server, "/second.csv", second, 200);
        serve(server, "/bad.csv", "not,a,trajectory\n".getBytes(StandardCharsets.UTF_8), 200);
        serve(server, "/missing.csv", new byte[0], 404);
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort();
            ObjectNode request = multiRequest();
            ObjectNode a = (ObjectNode) request.path("ballistics").get(0);
            ObjectNode b = (ObjectNode) request.path("ballistics").get(1);
            a.put("csvPath", uri + "/first.csv");
            b.put("csvPath", uri + "/second.csv").remove("baseLaunchUTC");
            JsonNode actual = search(request);
            assertCompatible(request, 200, actual);
            JsonNode targets = actual.path("allResults").get(0).path("targets");
            assertEquals(86400.0, targets.get(1).path("launchTimeJ2000Sec").asDouble()
                    - targets.get(0).path("launchTimeJ2000Sec").asDouble());
            assertEquals(uri + "/first.csv", targets.get(0).path("csvPath").asText());
            assertEquals(uri + "/second.csv", targets.get(1).path("csvPath").asText());
            assertNoScopes();
            b.put("baseLaunchUTC", "2026/07/01 07:57:10.000")
                    .put("baseLaunchUtc", "2026/07/01 07:58:10.000")
                    .put("baselaunchUTC", "2026/07/01 07:59:10.000");
            assertCompatible(request, 200, search(request));
            b.remove("baseLaunchUTC");
            assertCompatible(request, 200, search(request));
            b.remove("baseLaunchUtc");
            assertCompatible(request, 200, search(request));
            for (String failure : new String[]{"/bad.csv", "/missing.csv"}) {
                b.put("csvPath", uri + failure);
                int expectedStatus = failure.equals("/bad.csv") ? 400 : 500;
                JsonNode error = exchange(request, expectedStatus);
                // 远程坏表解析错误可能含下载临时路径，仍只断言原 error 层级；HTTP404的原URI错误可严格比较。
                if (expectedStatus == 500) assertCompatible(request, expectedStatus, error);
                assertEquals(1, error.size());
                assertTrue(error.has("error"));
                assertNoScopes();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 参数错误400与文件错误500保持原error对象和校验先后() throws Exception {
        ObjectNode request = multiRequest();
        request.put("stepSizeSec", 0);
        assertCompatible(request, 400, exchange(request, 400));
        request.put("stepSizeSec", 10);
        ((ObjectNode) request.path("ballistics").get(0)).put("csvPath", root.resolve("不存在.csv").toString());
        assertCompatible(request, 500, exchange(request, 500));
    }

    @Test
    void 旧单目标空文件位置保持原400错误文本() throws Exception {
        ObjectNode request = mapper.createObjectNode().put("ballisticCsvPath", " ");
        assertCompatible(request, 400, exchange(request, 400));
    }

    private ObjectNode multiRequest() throws Exception {
        ObjectNode request = (ObjectNode) STRICT_JSON.readTree(Files.readAllBytes(
                Paths.get("../test-data/baseline/ballistic/conditions.json"))).get(0).path("request").deepCopy();
        String source = Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv").toRealPath().toString();
        for (JsonNode target : request.path("ballistics")) {
            ((ObjectNode) target).put("csvPath", source);
        }
        return request;
    }

    private int countTrajectoryPoints(JsonNode node) {
        if (!node.isContainerNode()) return 0;
        int count = node.has("trajectoryPoints") ? node.path("trajectoryPoints").size() : 0;
        for (JsonNode child : node) count += countTrajectoryPoints(child);
        return count;
    }

    private void serve(HttpServer server, String path, byte[] content, int status) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, content.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(content); }
        });
    }

    private void assertNoScopes() throws Exception {
        try (Stream<Path> entries = Files.list(root.resolve("temp"))) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("scope-")));
        }
    }

    /** 默认构建完整比较兼容 Java 入口；显式验证模式再附加独立冻结比对，不以缺少副本静默跳过测试。 */
    private void assertCompatible(JsonNode request, int status, JsonNode actual) throws Exception {
        assertEquals(legacy(request, status), actual);
        assertFrozenMatches(request, status, actual);
    }

    /** 开启时只读冻结副本必须存在，否则立即失败；正常干净检出不依赖未提交的冻结文件。 */
    private void assertFrozenMatches(JsonNode request, int status, JsonNode actual) throws Exception {
        if (Boolean.getBoolean("ballistic.frozenReference")) {
            assertEquals(frozen(request, status), actual);
        }
    }

    /** 子优先加载只读冻结 com.tx 类，Spring 与 Jackson 共用测试依赖；避免拿迁入后的委托互相比作为唯一基准。 */
    private JsonNode frozen(JsonNode request, int status) throws Exception {
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
            Class<?> dto = loader.loadClass("com.tx.alg.api.VisibilityRequest");
            Class<?> controller = loader.loadClass("com.tx.alg.api.OrbitVisibilityController");
            ResponseEntity<?> response = (ResponseEntity<?>) controller.getMethod("search", dto)
                    .invoke(controller.newInstance(), mapper.treeToValue(request, dto));
            assertEquals(status, response.getStatusCodeValue());
            if (response.getBody() instanceof String) return STRICT_JSON.readTree((String) response.getBody());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ((StreamingResponseBody) response.getBody()).writeTo(output);
            return STRICT_JSON.readTree(output.toByteArray());
        }
    }

    JsonNode search(JsonNode request) throws Exception {
        return exchange(request, 200);
    }

    JsonNode exchange(JsonNode request, int statusCode) throws Exception {
        MvcResult pending = mvc.perform(post("/api/orbit/visibility/search")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(60000);
        return STRICT_JSON.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().is(statusCode))
                .andReturn().getResponse().getContentAsByteArray());
    }

    JsonNode legacy(JsonNode request, int status) throws Exception {
        ResponseEntity<?> response = new WindowEngineReference().search(mapper.treeToValue(request, VisibilityRequest.class));
        assertEquals(status, response.getStatusCodeValue());
        if (response.getBody() instanceof String) return STRICT_JSON.readTree((String) response.getBody());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);
        return STRICT_JSON.readTree(output.toByteArray());
    }
}
