package com.runisys.orbit.spacevisibility.application.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels.FileReference;
import com.runisys.orbit.spacevisibility.api.ballistic.BallisticRetimeController;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.BallisticFileConfiguration;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.BallisticFileStorage;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.ManagedFileException;
import com.runisys.orbit.spacevisibility.domain.ballistic.trajectory.TargetTrajectoryFitter;
import com.runisys.orbit.spacevisibility.domain.ballistic.trajectory.TargetTrajectoryFitter.RetimeInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实磁盘及原数值计算核验五字段复制、失败所有权、旧路径错误顺序与正在计算的输入租约。 */
class BallisticRetimeServiceTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 五字段调用者请求不变且Java默认仍为点目录() throws Exception {
        BallisticFileConfiguration config = config(10000000, 60000);
        BallisticRetimeService service = new BallisticRetimeService(new BallisticFileStorage(config, mapper));
        RetimeInput request = fixture(source());
        request.taskName = "五字段任务";
        request.targetId = "五字段目标";
        request.trajectoryCsvPath = "  " + request.trajectoryCsvPath + "  ";
        JsonNode before = mapper.valueToTree(request);
        TargetTrajectoryFitter.FitRunResult result = service.retime(request, false);
        assertEquals(before, mapper.valueToTree(request));
        assertEquals(".", new RetimeInput().outputDir);
        assertEquals("五字段任务", result.taskName);
        assertEquals("五字段目标", result.files.get(0).targetId);
        assertTrue(Paths.get(result.files.get(0).csvPath).toRealPath().startsWith(config.results.toRealPath()));
    }

    @Test
    void 请求源路径存在性与目录错误先后均保持原入口() throws Exception {
        BallisticFileConfiguration config = config(10000000, 60000);
        BallisticRetimeService service = new BallisticRetimeService(new BallisticFileStorage(config, mapper));
        RetimeInput missingPath = fixture(source());
        missingPath.trajectoryCsvPath = " "; missingPath.launchTimeUtc = " "; missingPath.outputDir = "bad\u0000dir";
        RetimeInput missingLaunch = fixture(source());
        missingLaunch.launchTimeUtc = null; missingLaunch.trajectoryCsvPath = "bad\u0000source";
        RetimeInput missingSource = fixture(root.resolve("not-found.csv"));
        missingSource.outputDir = "bad\u0000dir";
        RetimeInput invalidDirectory = fixture(source());
        invalidDirectory.outputDir = "bad\u0000dir";
        for (RetimeInput request : new RetimeInput[]{null, missingPath, missingLaunch, missingSource, invalidDirectory}) {
            Exception expected = assertThrows(Exception.class, () -> TargetTrajectoryFitter.retimeTrajectoryToCsv(request));
            Exception actual = assertThrows(Exception.class, () -> service.retime(request, true));
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(TargetTrajectoryFitter.describeFailure(expected), TargetTrajectoryFitter.describeFailure(actual));
        }
        assertEmpty(config.results); assertEmpty(config.metadata);
    }

    @Test
    void 计算中途失败和登记超限回滚自身显式目录及其他结果保留() throws Exception {
        BallisticFileConfiguration config = config(10000000, 60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        BallisticRetimeService service = new BallisticRetimeService(storage);
        java.util.List<String> lines = Files.readAllLines(source(), java.nio.charset.StandardCharsets.UTF_8);
        // 头和时刻均有效，第二行 ECEF 非数字令原 writer 已写部分输出后失败，确保检验实际部分 CSV 回滚。
        String[] badRow = lines.get(2).split(",", -1); badRow[6] = "invalid-position";
        Path bad = Files.write(root.resolve("bad.csv"), java.util.Arrays.asList(lines.get(0), lines.get(1), String.join(",", badRow)), java.nio.charset.StandardCharsets.UTF_8);
        RetimeInput request = fixture(bad);
        JsonNode before = mapper.valueToTree(request);
        assertThrows(IllegalArgumentException.class, () -> service.retime(request, false));
        assertEquals(before, mapper.valueToTree(request));
        assertEmpty(config.results); assertEmpty(config.metadata);
        Path explicit = Files.createDirectory(root.resolve("external"));
        Path kept = Files.write(explicit.resolve("keep.csv"), new byte[]{7});
        request.outputDir = explicit.toString();
        assertThrows(IllegalArgumentException.class, () -> service.retime(request, true));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(kept));
        try (Stream<Path> files = Files.list(explicit)) { assertEquals(2, files.count(), "原显式目录保留调用者文件及原 writer 的部分结果"); }

        BallisticFileConfiguration limitedConfig = config(16, 60000);
        BallisticFileStorage limited = new BallisticFileStorage(limitedConfig, mapper);
        Path other = Files.write(limitedConfig.results.resolve("other.csv"), new byte[]{3});
        String id = limited.registerResult(other.toString(), "other", "other").fileId;
        assertThrows(ManagedFileException.class, () -> new BallisticRetimeService(limited).retime(fixture(source()), false));
        try (BallisticFileStorage.Lease lease = limited.acquire(id)) { assertArrayEquals(new byte[]{3}, Files.readAllBytes(lease.path)); }
        try (Stream<Path> files = Files.list(limitedConfig.results)) { assertEquals(1, files.count()); }
    }

    @Test
    void 活动上传及结果输入从读取前到登记完成均受租约保护() throws Exception {
        for (boolean uploaded : new boolean[]{true, false}) {
            BallisticFileConfiguration config = config(10000000, 1);
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            Path[] leasedInput = new Path[1];
            BallisticFileStorage storage = new BallisticFileStorage(config, mapper) {
                @Override public Lease acquirePath(String location) throws IOException {
                    Lease lease = super.acquirePath(location);
                    assertNotNull(lease);
                    leasedInput[0] = lease.path;
                    acquired.countDown();
                    try { if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("测试租约等待超时"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    return lease;
                }
                @Override public synchronized com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels.FileReference registerResult(
                        String path, String task, String target) throws IOException {
                    if (leasedInput[0] != null) {
                        // 登记阶段再清理一次：输入租约必须一直覆盖原读取、重定时写出和输出登记，不能提前释放。
                        try { Thread.sleep(20); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                        cleanupExpired();
                        assertTrue(Files.exists(leasedInput[0]), "自动登记完成之前输入仍然活动");
                    }
                    return super.registerResult(path, task, target);
                }
            };
            String location;
            String id;
            if (uploaded) {
                com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels.FileReference reference = storage.upload(
                        new ByteArrayInputStream(Files.readAllBytes(source())), "source.csv", "text/csv", "retime");
                location = reference.serverPath; id = reference.fileId;
            } else {
                Path generated = Files.copy(source(), config.results.resolve("source-result.csv"));
                com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels.FileReference reference = storage.registerResult(generated.toString(), "source", "source");
                location = reference.serverPath; id = reference.fileId;
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<TargetTrajectoryFitter.FitRunResult> running = executor.submit(() -> new BallisticRetimeService(storage).retime(fixture(Paths.get(location)), false));
                assertTrue(acquired.await(10, TimeUnit.SECONDS));
                Thread.sleep(20);
                storage.cleanupExpired();
                assertTrue(Files.exists(Paths.get(location)), "活动输入不能在原 readAllLines 之前被清理");
                try (BallisticFileStorage.Lease lease = storage.acquire(id)) { assertTrue(Files.size(lease.path) > 0); }
                resume.countDown();
                TargetTrajectoryFitter.FitRunResult result = running.get(10, TimeUnit.SECONDS);
                assertArrayEquals(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/retime/trajectory-0.csv")), Files.readAllBytes(Paths.get(result.files.get(0).csvPath)));
                Thread.sleep(20); storage.cleanupExpired();
                assertFalse(Files.exists(Paths.get(location)), "计算退出已释放输入租约，过期输入可清理");
            } finally { resume.countDown(); executor.shutdownNow(); }
        }
    }

    @Test
    void 管理输入变大保持原计算500包体且目录错误仍先于新增限额() throws Exception {
        BallisticFileConfiguration config = config(32, 60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels.FileReference uploaded = storage.upload(new ByteArrayInputStream(new byte[]{1}), "source.csv", "text/csv", "retime");
        Files.write(Paths.get(uploaded.serverPath), Files.readAllBytes(source()));
        BallisticRetimeService service = new BallisticRetimeService(storage);
        BallisticRetimeController controller = new BallisticRetimeController(service, mapper);
        RetimeInput request = fixture(Paths.get(uploaded.serverPath));
        ResponseEntity<?> response = controller.retime(mapper.valueToTree(request));
        assertEquals(500, response.getStatusCodeValue());
        assertEquals(mapper.readTree("{\"error\":\"ManagedFileException: 文件超过配置字节上限\"}"), mapper.valueToTree(response.getBody()));
        assertEmpty(config.results);
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{7});
        for (String directory : new String[]{"bad\u0000dir", blocked.toString()}) {
            request.outputDir = directory;
            Exception expected = assertThrows(Exception.class, () -> TargetTrajectoryFitter.retimeTrajectoryToCsv(request));
            Exception actual = assertThrows(Exception.class, () -> service.retime(request, true));
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(TargetTrajectoryFitter.describeFailure(expected), TargetTrajectoryFitter.describeFailure(actual));
        }
    }

    @Test
    void 输入租约关闭一次失败返回原500并回滚自身缺省结果() throws Exception {
        verifyInputCloseFailure(false, true);
        verifyInputCloseFailure(false, false);
    }

    @Test
    void 输入租约关闭一次失败保留显式目录调用者文件和原输出() throws Exception {
        verifyInputCloseFailure(true, true);
        verifyInputCloseFailure(true, false);
    }

    /**
     * 仅在真实黄金重定时及输出登记成功后，为指定输入的关闭 metadata 写入注入一次 IOException。
     * 上传与已生成输入均执行真实计算；其它 IO 正常，分别核验独占回滚和显式保留，不模拟空计算成功。
     */
    private void verifyInputCloseFailure(boolean explicit, boolean uploaded) throws Exception {
        BallisticFileConfiguration config = new BallisticFileConfiguration(
                Files.createTempDirectory(root, "close-").toString(), "", "", "", "", 10000000, 60000, 500);
        InputCloseFailureMapper failureMapper = new InputCloseFailureMapper();
        FileReference[] output = new FileReference[1];
        byte[] golden = Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/retime/trajectory-0.csv"));
        BallisticFileStorage storage = new BallisticFileStorage(config, failureMapper) {
            @Override public synchronized FileReference registerResult(String csv, String task, String target) throws IOException {
                FileReference reference = super.registerResult(csv, task, target);
                if (failureMapper.inputId != null) {
                    assertArrayEquals(golden, Files.readAllBytes(Paths.get(csv)), "关闭失败前原数值 CSV 已完整写出并登记");
                    output[0] = reference;
                    failureMapper.armed = true;
                }
                return reference;
            }
        };
        Path otherPath = Files.write(config.results.resolve("other.csv"), new byte[]{3, 7});
        FileReference other = storage.registerResult(otherPath.toString(), "other", "other");
        FileReference input = uploaded
                ? storage.upload(new ByteArrayInputStream(Files.readAllBytes(source())), "source.csv", "text/csv", "retime")
                : storage.registerResult(Files.copy(source(), config.results.resolve("source-result.csv")).toString(), "source", "source");
        failureMapper.inputId = input.fileId;
        byte[] otherMetadata = Files.readAllBytes(config.metadata.resolve(other.fileId + ".json"));
        java.util.Set<Path> originalResults;
        try (Stream<Path> files = Files.list(config.results)) { originalResults = files.collect(java.util.stream.Collectors.toSet()); }
        Path explicitDirectory = explicit ? Files.createDirectory(config.results.resolve("explicit")) : null;
        Path kept = explicit ? Files.write(explicitDirectory.resolve("keep.csv"), new byte[]{9}) : null;
        RetimeInput request = fixture(Paths.get(input.serverPath));
        if (explicit) request.outputDir = explicitDirectory.toString();
        ObjectNode body = failureMapper.valueToTree(request);
        if (!explicit) body.remove("outputDir");
        ResponseEntity<?> response = new BallisticRetimeController(new BallisticRetimeService(storage), failureMapper).retime(body);
        assertEquals(500, response.getStatusCodeValue());
        assertEquals(mapper.readTree("{\"error\":\"IOException: 注入输入租约关闭metadata持久化失败\"}"), mapper.valueToTree(response.getBody()));
        assertEquals(1, failureMapper.injected, "仅指定输入关闭的一次 metadata 写入失败，其它 IO 正常");
        assertNotNull(output[0]);
        assertArrayEquals(Files.readAllBytes(source()), Files.readAllBytes(Paths.get(input.serverPath)));
        assertArrayEquals(otherMetadata, Files.readAllBytes(config.metadata.resolve(other.fileId + ".json")), "另一结果登记未被回滚或刷新");
        if (explicit) {
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(kept));
            assertArrayEquals(golden, Files.readAllBytes(Paths.get(output[0].serverPath)));
            try (Stream<Path> files = Files.list(explicitDirectory)) { assertEquals(2, files.count()); }
            try (BallisticFileStorage.Lease lease = storage.acquire(output[0].fileId)) { assertArrayEquals(golden, Files.readAllBytes(lease.path)); }
        } else {
            try (Stream<Path> files = Files.list(config.results)) {
                assertEquals(originalResults, files.collect(java.util.stream.Collectors.toSet()), "缺省失败删除自身独占目录，保留其它结果");
            }
            assertFalse(Files.exists(config.metadata.resolve(output[0].fileId + ".json")), "自身输出登记必须回滚");
            assertThrows(ManagedFileException.class, () -> storage.acquire(output[0].fileId), "内存登记也必须回滚");
        }
        try (Stream<Path> files = Files.list(config.metadata)) { assertEquals(explicit ? 3 : 2, files.count(), "没有遗留所有权标记或 pending 文件"); }
        try (BallisticFileStorage.Lease lease = storage.acquire(input.fileId)) { assertArrayEquals(Files.readAllBytes(source()), Files.readAllBytes(lease.path)); }
        try (BallisticFileStorage.Lease lease = storage.acquire(other.fileId)) { assertArrayEquals(new byte[]{3, 7}, Files.readAllBytes(lease.path)); }
    }

    /** 通过输入 ID 精确限定故障，输出登记后才启用，确保失败来自 Lease.close 而不是 acquire 或输出写入。 */
    private static class InputCloseFailureMapper extends ObjectMapper {
        String inputId;
        boolean armed;
        int injected;

        @Override public void writeValue(File file, Object value) throws IOException {
            if (armed && value instanceof BallisticFileStorage.Record
                    && ((BallisticFileStorage.Record) value).reference.fileId.equals(inputId)) {
                armed = false;
                injected++;
                throw new IOException("注入输入租约关闭metadata持久化失败");
            }
            super.writeValue(file, value);
        }
    }

    private BallisticFileConfiguration config(long bytes, long ttl) throws IOException {
        return new BallisticFileConfiguration(root.toString(), "", "", "", "", bytes, ttl, 500);
    }
    private Path source() throws IOException { return Paths.get("../test-data/baseline/ballistic/simulate/trajectory-0.csv").toRealPath(); }
    private RetimeInput fixture(Path source) {
        RetimeInput request = new RetimeInput(); request.trajectoryCsvPath = source.toString();
        request.taskName = "T04-retime"; request.launchTimeUtc = "2026-07-02 07:57:00.000"; return request;
    }
    private void assertEmpty(Path directory) throws IOException { try (Stream<Path> files = Files.list(directory)) { assertEquals(0, files.count()); } }
}
