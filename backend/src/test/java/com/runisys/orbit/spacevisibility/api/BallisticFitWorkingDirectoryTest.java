package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runisys.orbit.spacevisibility.SpaceTargetVisibleApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 JVM 在系统临时工作目录启动真实服务，验证三种轨迹接口显式点目录，又不把测试输出写进源码。 */
class BallisticFitWorkingDirectoryTest {
    @TempDir
    Path root;

    @Test
    void 显式点空串和null保留原工作目录语义且不进入管理目录() throws Exception {
        verifyWorkingDirectory("target_trajectory_fit_package102_default.json", "/api/trajectory/fit/package102");
    }

    @Test
    void 模拟显式点空串和null保持原工作目录及外部登记拒绝() throws Exception {
        verifyWorkingDirectory("target_trajectory_simulate_default.json", "/api/trajectory/simulate");
    }

    @Test
    void 重定时显式点空串和null保持原工作目录及外部登记拒绝() throws Exception {
        verifyWorkingDirectory("../../baseline/ballistic/simulate/trajectory-0.csv", "/api/trajectory/retime");
    }

    private void verifyWorkingDirectory(String fixture, String endpoint) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String[] entries = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (int i = 0; i < entries.length; i++) entries[i] = Paths.get(entries[i]).toAbsolutePath().toString();
        Path log = root.resolve("working-directory-http.log");
        Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", String.join(File.pathSeparator, entries),
                getClass().getName(), Paths.get("../test-data/reference/ballistic/" + fixture)
                        .toRealPath().toString(), endpoint)
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "独立 HTTP 工作目录验证超时");
        assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        assertTrue(Files.exists(root.resolve("verified.txt")));
    }

    /** 子进程入口只用于测试，启动正式 Boot 并以 HTTP 提交原字段；不改变 user.dir 或拟合器路径规则。 */
    public static void main(String[] args) throws Exception {
        assertTrue(java.awt.GraphicsEnvironment.isHeadless());
        try (ConfigurableApplicationContext context = SpringApplication.run(SpaceTargetVisibleApplication.class,
                "--server.port=0", "--ballistic.files.root=managed",
                "--space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            TestRestTemplate http = new TestRestTemplate();
            for (String directory : new String[]{".", " ", null}) {
                ObjectNode request = args[1].endsWith("/retime")
                        ? mapper.createObjectNode().put("trajectoryCsvPath", args[0]).put("launchTimeUtc", "2026-07-02 07:57:00.000")
                        : (ObjectNode) mapper.readTree(Files.readAllBytes(Paths.get(args[0])));
                if (directory == null) request.putNull("outputDir");
                else request.put("outputDir", directory);
                ResponseEntity<JsonNode> response = http.postForEntity(base + args[1], request, JsonNode.class);
                assertEquals(200, response.getStatusCodeValue());
                for (JsonNode file : response.getBody().path("files")) {
                    Path csv = Paths.get(file.path("csvPath").asText());
                    assertEquals(Paths.get(".").toRealPath(), csv.getParent().toRealPath());
                    assertTrue(Files.size(csv) > 0);
                    assertEquals(400, http.postForEntity(base + "/api/v1/ballistic/files/register-result",
                            mapper.createObjectNode().put("csvPath", csv.toString()), JsonNode.class).getStatusCodeValue());
                }
            }
        }
        Files.write(Paths.get("verified.txt"), "explicit dot, blank and null: HTTP 200; managed registration: 400; headless=true"
                .getBytes(StandardCharsets.UTF_8));
    }
}
