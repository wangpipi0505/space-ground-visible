package com.runisys.orbit.spacevisibility.infrastructure.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticFitService;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import com.tx.alg.trajectory.TargetTrajectoryFitter.FitRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 结果生命周期用真实磁盘、真实拟合失败与 Windows Junction 覆盖，禁止删除调用者既有文件。 */
class BallisticResultWorkspaceTest {
    @TempDir
    Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private BallisticFileConfiguration config(long bytes, long ttl) throws IOException {
        return new BallisticFileConfiguration(root.toString(), "", "", "", "", bytes, ttl, 500);
    }

    @Test
    void 同时非法的观测和目录保持原校验优先级() throws Exception {
        BallisticFitService service = new BallisticFitService(new BallisticFileStorage(config(1000000, 60000), mapper));
        FitRequest invalid = new FitRequest();
        invalid.outputDir = "invalid\u0000directory";
        Exception original = assertThrows(Exception.class, () -> TargetTrajectoryFitter.fitPackage102ToCsv(invalid));
        Exception actual = assertThrows(Exception.class, () -> service.fitPackage102(invalid, true));
        assertEquals(original.getClass(), actual.getClass());
        assertEquals(TargetTrajectoryFitter.describeFailure(original), TargetTrajectoryFitter.describeFailure(actual));
        FitRequest invalidStep = fixture();
        invalidStep.interpolationStepSec = 0;
        invalidStep.outputDir = invalid.outputDir;
        Exception originalStep = assertThrows(Exception.class, () -> TargetTrajectoryFitter.fitPackage102ToCsv(invalidStep));
        Exception actualStep = assertThrows(Exception.class, () -> service.fitPackage102(invalidStep, true));
        assertEquals(TargetTrajectoryFitter.describeFailure(originalStep), TargetTrajectoryFitter.describeFailure(actualStep));
    }

    @Test
    void 清理不得凭fit前缀推定调用者目录属于本请求() throws Exception {
        BallisticFileConfiguration config = config(100, 1);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        Path caller = Files.createDirectory(config.results.resolve("fit-caller-owned"));
        Path preserved = Files.write(caller.resolve("keep.csv"), new byte[]{7});
        Files.setLastModifiedTime(caller, FileTime.fromMillis(1));
        try (BallisticFileStorage.ResultWorkspace explicit = storage.resultWorkspace(caller.toString(), true)) {
            explicit.complete();
        }
        storage.cleanupExpired();
        assertTrue(Files.exists(preserved), "显式目录不能仅因前缀相同就按崩溃遗留清理");
    }

    @Test
    void 重启仅回收持久标记的崩溃遗留目录() throws Exception {
        BallisticFileConfiguration config = config(100, 1);
        BallisticFileStorage crashed = new BallisticFileStorage(config, mapper);
        // 不关闭旧实例作用域模拟进程在未登记部分 CSV 后退出；新实例必须只认持久所有权标记。
        BallisticFileStorage.ResultWorkspace abandoned = crashed.resultWorkspace(null, false);
        Files.write(abandoned.directory.resolve("partial.csv"), new byte[]{1});
        Files.setLastModifiedTime(abandoned.directory, FileTime.fromMillis(1));
        BallisticFileStorage restarted = new BallisticFileStorage(config, mapper);
        restarted.cleanupExpired();
        assertFalse(Files.exists(abandoned.directory));
        assertEmpty(config.metadata);
    }

    @Test
    void 显式复用旧独占目录后不能再整目录回收调用者文件() throws Exception {
        BallisticFileConfiguration config = config(100, 1);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        Path directory;
        try (BallisticFileStorage.ResultWorkspace initial = storage.resultWorkspace(null, false)) {
            directory = initial.directory;
            initial.complete();
        }
        BallisticFileStorage restarted = new BallisticFileStorage(config, mapper);
        try (BallisticFileStorage.ResultWorkspace explicit = restarted.resultWorkspace(directory.toString(), true)) {
            Files.write(directory.resolve("caller.csv"), new byte[]{7});
            explicit.complete();
        }
        Files.setLastModifiedTime(directory, FileTime.fromMillis(1));
        restarted.cleanupExpired();
        assertTrue(Files.exists(directory.resolve("caller.csv")));
    }

    @Test
    void 结果写入和多重作用域阻止TTL成功退出才开始保留期() throws Exception {
        BallisticFileConfiguration config = config(100, 1);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        String id;
        Path csv;
        try (BallisticFileStorage.ResultWorkspace one = storage.resultWorkspace(null, false)) {
            csv = Files.write(one.directory.resolve("result.csv"), new byte[]{1});
            one.register(csv.toString(), "task", "target");
            id = storage.registerResult(csv.toString(), "task", "target").fileId;
            try (BallisticFileStorage.ResultWorkspace two = storage.resultWorkspace(one.directory.toString(), true)) {
                Thread.sleep(20);
                storage.cleanupExpired();
                assertTrue(Files.exists(csv));
                two.complete();
            }
            Thread.sleep(20);
            storage.cleanupExpired();
            assertTrue(Files.exists(csv));
            one.complete();
        }
        try (BallisticFileStorage.Lease lease = storage.acquire(id)) {
            Thread.sleep(20);
            storage.cleanupExpired();
            assertTrue(Files.exists(csv));
        }
        Thread.sleep(20);
        storage.cleanupExpired();
        assertFalse(Files.exists(csv));
        assertThrows(ManagedFileException.class, () -> storage.acquire(id));
    }

    @Test
    void 显式祖先结果作用域仅保护结果而无关过期上传仍清理() throws Exception {
        BallisticFileConfiguration config = config(100, 1);
        BallisticFileStorage initial = new BallisticFileStorage(config, mapper);
        String uploadId = initial.upload(new ByteArrayInputStream(new byte[]{7}), "input.csv", "text/csv",
                "trajectory").fileId;
        Path csv = Files.write(config.results.resolve("result.csv"), new byte[]{1});
        String resultId = initial.registerResult(csv.toString(), "task", "target").fileId;
        BallisticFileStorage.Record upload = expireRecord(config, uploadId);
        expireRecord(config, resultId);
        // 重启从真实持久元数据恢复旧时间，避免休眠竞态；祖先目录同时包含 uploads 和 results。
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        try (BallisticFileStorage.ResultWorkspace scope = storage.resultWorkspace(root.toString(), true)) {
            storage.cleanupExpired();
            assertTrue(Files.exists(csv), "活动结果目录下登记结果必须继续受保护");
            assertFalse(Files.exists(Paths.get(upload.reference.serverPath)), "未取得输入租约的上传应独立按 TTL 清理");
            assertFalse(Files.exists(config.metadata.resolve(uploadId + ".json")));
            scope.complete();
        }
    }

    @Test
    void 显式祖先结果作用域关闭仅刷新结果而不刷新未引用上传() throws Exception {
        BallisticFileConfiguration config = config(100, 60000);
        BallisticFileStorage initial = new BallisticFileStorage(config, mapper);
        String uploadId = initial.upload(new ByteArrayInputStream(new byte[]{7}), "input.csv", "text/csv",
                "trajectory").fileId;
        Path csv = Files.write(config.results.resolve("result.csv"), new byte[]{1});
        String resultId = initial.registerResult(csv.toString(), "task", "target").fileId;
        expireRecord(config, uploadId);
        expireRecord(config, resultId);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        try (BallisticFileStorage.ResultWorkspace scope = storage.resultWorkspace(root.toString(), true)) {
            scope.complete();
        }
        // 直接读持久 JSON，不调用 acquire/registerResult，避免测试自身触碰最后使用时间。
        assertTrue(readRecord(config, resultId).lastAccessMillis > 1, "关闭作用域应刷新相关结果的保留期");
        assertEquals(1, readRecord(config, uploadId).lastAccessMillis, "未读取的上传不应因结果作用域关闭获得新 TTL");
    }

    @Test
    void 默认目录部分拟合失败和登记超限均回滚而显式目录保留() throws Exception {
        BallisticFileConfiguration config = config(10000000, 60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        FitRequest partial = fixture();
        // 保留第一目标四点使其先成功写出，第二目标只留一点，原拟合器在第二目标才失败。
        partial.package102 = new java.util.ArrayList<>(partial.package102.subList(0, 5));
        BallisticFitService service = new BallisticFitService(storage);
        assertThrows(IllegalArgumentException.class, () -> service.fitPackage102(partial, false));
        assertEmpty(config.results);
        assertEmpty(config.metadata);
        Path caller = Files.createDirectory(root.resolve("explicit"));
        Path preserved = Files.write(caller.resolve("keep.csv"), new byte[]{7});
        partial.outputDir = caller.toString();
        assertThrows(IllegalArgumentException.class, () -> service.fitPackage102(partial, true));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(preserved));
        try (Stream<Path> files = Files.list(caller)) {
            assertEquals(2, files.count(), "显式失败目录中旧文件与已生成第一目标都按原语义保留");
        }
        BallisticFileStorage limited = new BallisticFileStorage(config(16, 60000), mapper);
        assertThrows(ManagedFileException.class, () -> new BallisticFitService(limited).fitPackage102(fixture(), false));
        assertEmpty(config.results);
        assertEmpty(config.metadata);
    }

    @Test
    void 部分登记后失败会注销自身登记且不删除其他结果() throws Exception {
        BallisticFileConfiguration config = config(100, 60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        Path preserved = Files.write(config.results.resolve("keep.csv"), new byte[]{7});
        String keepId = storage.registerResult(preserved.toString(), "keep", "keep").fileId;
        String rollbackId;
        Path owned;
        try (BallisticFileStorage.ResultWorkspace scope = storage.resultWorkspace(null, false)) {
            owned = scope.directory;
            Path file = Files.write(owned.resolve("partial.csv"), new byte[]{1});
            scope.register(file.toString(), "task", "target");
            rollbackId = storage.registerResult(file.toString(), "task", "target").fileId;
        }
        assertFalse(Files.exists(owned));
        assertThrows(ManagedFileException.class, () -> storage.acquire(rollbackId));
        try (BallisticFileStorage.Lease lease = storage.acquire(keepId)) {
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(lease.path));
        }
    }

    @Test
    void 默认结果失败清理不能沿WindowsJunction进入外部目录() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        BallisticFileStorage storage = new BallisticFileStorage(config(100, 60000), mapper);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path preserved = Files.write(outside.resolve("keep.csv"), new byte[]{7});
        Path owned;
        try (BallisticFileStorage.ResultWorkspace scope = storage.resultWorkspace(null, false)) {
            owned = scope.directory;
            Path junction = scope.directory.resolve("escape");
            String command = "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                    + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null";
            assertEquals(0, new ProcessBuilder("powershell", "-NoProfile", "-Command", command).start().waitFor());
            assertThrows(IllegalArgumentException.class,
                    () -> scope.register(junction.resolve("keep.csv").toString(), "task", "target"));
        }
        assertFalse(Files.exists(owned));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(preserved));
    }

    private FitRequest fixture() throws IOException {
        return mapper.readValue(Files.readAllBytes(Paths.get(
                "../test-data/reference/ballistic/target_trajectory_fit_package102_default.json")), FitRequest.class);
    }

    private BallisticFileStorage.Record expireRecord(BallisticFileConfiguration config, String id) throws IOException {
        BallisticFileStorage.Record record = readRecord(config, id);
        record.lastAccessMillis = 1;
        mapper.writeValue(config.metadata.resolve(id + ".json").toFile(), record);
        return record;
    }

    private BallisticFileStorage.Record readRecord(BallisticFileConfiguration config, String id) throws IOException {
        return mapper.readValue(config.metadata.resolve(id + ".json").toFile(), BallisticFileStorage.Record.class);
    }

    private void assertEmpty(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }
}
