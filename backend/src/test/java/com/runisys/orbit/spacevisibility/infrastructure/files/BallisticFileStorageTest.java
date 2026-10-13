package com.runisys.orbit.spacevisibility.infrastructure.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.api.ballistic.BallisticFileModels.FileReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/** 文件系统实测覆盖重启、实际流限额、失败清理、活动引用和真实路径越界。 */
class BallisticFileStorageTest {
    @TempDir
    Path root;

    private BallisticFileConfiguration configuration(long ttl) throws IOException {
        return new BallisticFileConfiguration(root.toString(), "", "", "", "", 16, ttl, 500);
    }

    @Test
    void 持久登记重启后同一ID仍可读取且原文件名不参与路径() throws Exception {
        BallisticFileConfiguration config = configuration(60000);
        BallisticFileStorage first = new BallisticFileStorage(config, new ObjectMapper());
        FileReference reference = first.upload(new ByteArrayInputStream(new byte[]{0, 1, 2}),
                "C:\\fakepath\\弹道.csv", "text/csv", "trajectory");
        assertEquals("弹道.csv", reference.originalName);
        assertFalse(reference.serverPath.contains(reference.fileId));
        BallisticFileStorage restarted = new BallisticFileStorage(config, new ObjectMapper());
        try (BallisticFileStorage.Lease lease = restarted.acquire(reference.fileId)) {
            assertArrayEquals(new byte[]{0, 1, 2}, Files.readAllBytes(lease.path));
        }
        assertThrows(ManagedFileException.class, () -> restarted.acquire("../../results/a.csv"));
    }

    @Test
    void 实际流超限与中途失败均不残留上传或登记文件() throws Exception {
        BallisticFileConfiguration config = configuration(60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        ManagedFileException over = assertThrows(ManagedFileException.class,
                () -> storage.upload(new ByteArrayInputStream(new byte[17]), "a.csv", "text/csv", null));
        assertEquals(413, over.status);
        InputStream failed = new InputStream() {
            int count;
            @Override
            public int read() throws IOException {
                if (++count > 3) {
                    throw new IOException("模拟传输中断");
                }
                return 1;
            }
        };
        assertThrows(IOException.class, () -> storage.upload(failed, "a.csv", "text/csv", null));
        assertCount(config.uploads, 0);
        assertCount(config.metadata, 0);
        assertThrows(IllegalArgumentException.class, () -> storage.upload(new ByteArrayInputStream(new byte[0]),
                "a\r\nb.csv", "text/csv", null));
        assertThrows(IllegalArgumentException.class, () -> storage.upload(new ByteArrayInputStream(new byte[0]),
                "a.csv", "text/csv\r\nX-Bad: yes", null));
    }

    @Test
    void 过期清理保护全部活动租约关闭后才删除并注销ID() throws Exception {
        BallisticFileConfiguration config = configuration(1);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        FileReference reference = storage.upload(new ByteArrayInputStream(new byte[]{1}), "a.csv", "text/csv", null);
        BallisticFileStorage.Lease one = storage.acquire(reference.fileId);
        BallisticFileStorage.Lease two = storage.acquirePath(reference.serverPath);
        Thread.sleep(20);
        storage.cleanupExpired();
        assertTrue(Files.exists(one.path));
        one.close();
        Thread.sleep(20);
        storage.cleanupExpired();
        assertTrue(Files.exists(two.path));
        two.close();
        Thread.sleep(20);
        storage.cleanupExpired();
        assertFalse(Files.exists(Paths.get(reference.serverPath)));
        assertThrows(ManagedFileException.class, () -> storage.acquire(reference.fileId));
        assertCount(config.metadata, 0);
    }

    @Test
    void 两侧空白的已登记路径仍持有租约并保护过期文件() throws Exception {
        BallisticFileConfiguration config = configuration(1);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        FileReference reference = storage.upload(new ByteArrayInputStream(new byte[]{1}), "a.csv", "text/csv", null);
        // 旧解析器会去掉位置字段两侧空白；租约查找必须指向相同实体，不能被 TTL 扫描漏删。
        try (BallisticFileStorage.Lease lease = storage.acquirePath("  " + reference.serverPath + "  ")) {
            assertNotNull(lease, "带两侧空白的已登记路径必须获取真实租约");
            assertEquals(Paths.get(reference.serverPath).toRealPath(), lease.path);
            assertEquals(reference.fileId, lease.reference.fileId);
            Thread.sleep(20);
            storage.cleanupExpired();
            assertTrue(Files.exists(lease.path), "解析期间即使超过 TTL 也不能删除活动文件");
        }
        Thread.sleep(20);
        storage.cleanupExpired();
        assertFalse(Files.exists(Paths.get(reference.serverPath)));
        assertThrows(ManagedFileException.class, () -> storage.acquire(reference.fileId));
    }

    @Test
    void 远程工作目录活动受保护失败关闭与崩溃遗留过期均删除() throws Exception {
        BallisticFileConfiguration config = configuration(1);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        Path active;
        try (BallisticFileStorage.TemporaryWorkspace workspace = storage.temporaryWorkspace()) {
            active = workspace.directory;
            Files.write(active.resolve("partial.csv"), new byte[]{1});
            Thread.sleep(20);
            storage.cleanupExpired();
            assertTrue(Files.exists(active));
        }
        assertFalse(Files.exists(active));
        Path orphan = Files.createDirectory(config.temp.resolve("scope-crash"));
        Files.write(orphan.resolve("partial.csv"), new byte[]{1});
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(1));
        storage.cleanupExpired();
        assertFalse(Files.exists(orphan));
        Path uploadOrphan = Files.write(config.uploads.resolve("upload-crash.csv"), new byte[]{1});
        Files.setLastModifiedTime(uploadOrphan, FileTime.fromMillis(1));
        storage.cleanupExpired();
        assertFalse(Files.exists(uploadOrphan));
    }

    @Test
    void 结果边界拒绝外部文件及登记后替换的符号链接() throws Exception {
        BallisticFileConfiguration config = configuration(60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        Path outside = Files.write(root.resolve("outside.csv"), new byte[]{7});
        assertThrows(IllegalArgumentException.class, () -> storage.registerResult(outside.toString(), null, null));
        Path result = Files.write(config.results.resolve("result.csv"), new byte[]{1});
        FileReference reference = storage.registerResult(result.toString(), null, null);
        Files.delete(result);
        try {
            Files.createSymbolicLink(result, outside.toRealPath());
        } catch (java.nio.file.FileSystemException ex) {
            // Windows 当前令牌无符号链接权限时明确记录 skipped，另用真实 Junction 验证目录链接边界。
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接未执行：" + ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> storage.acquire(reference.fileId));
        assertThrows(IllegalArgumentException.class, () -> storage.registerResult(result.toString(), null, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(outside));
    }

    @Test
    void Windows真实Junction不能穿出结果目录() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        BallisticFileConfiguration config = configuration(60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        Path outside = Files.createDirectory(root.resolve("outside-directory"));
        Files.write(outside.resolve("secret.csv"), new byte[]{7});
        Path junction = config.results.resolve("junction");
        String command = "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null";
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command", command).start();
        assertEquals(0, process.waitFor(), "创建测试 Junction 应成功");
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> storage.registerResult(junction.resolve("secret.csv").toString(), null, null));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(outside.resolve("secret.csv")));
        } finally {
            Files.delete(junction);
        }
    }

    @Test
    void 临时目录清理不能沿WindowsJunction删除外部文件() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        BallisticFileConfiguration config = configuration(60000);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        Path outside = Files.createDirectory(root.resolve("keep-outside"));
        Path preserved = Files.write(outside.resolve("keep.csv"), new byte[]{7});
        try (BallisticFileStorage.TemporaryWorkspace workspace = storage.temporaryWorkspace()) {
            Path junction = workspace.directory.resolve("escape");
            String command = "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                    + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null";
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command", command).start();
            assertEquals(0, process.waitFor());
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(preserved));
    }

    private static void assertCount(Path directory, long expected) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(expected, files.count());
        }
    }
}
