package com.runisys.orbit.spacevisibility.infrastructure.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.api.ballistic.BallisticFileModels.FileReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本机受管理文件登记表。JSON 元数据原子替换、重启恢复；ID 与磁盘路径分别生成随机值。
 * 所有登记/租约/清理在本实例锁内完成，下载持有租约但不持有锁，避免长下载阻塞所有请求。
 * 单后端进程使用一个目录；不以文件名或用户提交的 ID 推导访问路径。
 */
@Component
public class BallisticFileStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(BallisticFileStorage.class);
    private final BallisticFileConfiguration config;
    private final ObjectMapper mapper;
    private final Map<String, Record> records = new HashMap<String, Record>();
    private final Map<String, Integer> active = new HashMap<String, Integer>();
    private final java.util.Set<Path> activeWorkspaces = new java.util.HashSet<Path>();
    /** 结果写入期间仅保护目录下已登记结果；每个调用独立计数，显式相同目录的并发请求也不会提前解锁。 */
    private final java.util.Set<ResultWorkspace> activeResults = new java.util.HashSet<ResultWorkspace>();

    public BallisticFileStorage(BallisticFileConfiguration config, ObjectMapper mapper) throws IOException {
        this.config = config;
        this.mapper = mapper;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(config.metadata, "*.json")) {
            for (Path file : files) {
                try {
                    Record record = mapper.readValue(checked(config.metadata, file).toFile(), Record.class);
                    if (record.reference == null || record.reference.fileId == null
                            || !file.getFileName().toString().equals(record.reference.fileId + ".json")) {
                        throw new IOException("登记键与元数据文件不匹配");
                    }
                    checked(rootOf(record), Paths.get(record.reference.serverPath));
                    records.put(record.reference.fileId, record);
                } catch (IOException | RuntimeException ex) {
                    LOGGER.warn("忽略失效的文件登记：{}，原因：{}", file.getFileName(), ex.getMessage());
                }
            }
        }
    }

    /**
     * 实际读取每个字节后判断上限，不相信 Content-Length 或 MultipartFile 的声明大小。
     * 失败时清除未登记实体；原文件名仅作显示，磁盘名称独立生成且保留解析所需后缀。
     */
    public synchronized FileReference upload(InputStream input, String originalName, String contentType,
                                             String purpose) throws IOException {
        String safeName = safeName(originalName);
        String type = safeContentType(contentType);
        Path file = Files.createTempFile(config.uploads, "upload-", suffix(safeName));
        boolean stored = false;
        try {
            checked(config.uploads, file);
            long size = 0;
            try (OutputStream output = Files.newOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > config.maxBytes) {
                        throw new ManagedFileException(413, "FILE_TOO_LARGE", "文件超过配置字节上限");
                    }
                    output.write(buffer, 0, count);
                }
            }
            Record record = record(file, safeName, type, true);
            record.purpose = purpose;
            save(record);
            records.put(record.reference.fileId, record);
            stored = true;
            return copy(record.reference);
        } finally {
            if (!stored) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** 登记已有结果，路径先验证真实位置；同一真实文件复用 ID，任务关联保存在登记元数据中。 */
    public synchronized FileReference registerResult(String csvPath, String taskName, String targetId) throws IOException {
        if (csvPath == null || csvPath.trim().isEmpty()) {
            throw new IllegalArgumentException("csvPath 不能为空");
        }
        Path path;
        try {
            path = checked(config.results, Paths.get(csvPath));
        } catch (java.nio.file.NoSuchFileException ex) {
            // 只有请求指向的结果文件缺失才是404；metadata等基础设施IO异常应由辅助层返回500。
            throw missing();
        }
        if (Files.size(path) > config.maxBytes) {
            throw new ManagedFileException(413, "FILE_TOO_LARGE", "结果文件超过配置字节上限");
        }
        for (Record existing : records.values()) {
            if (existing.reference.serverPath.equals(path.toString())) {
                existing.taskName = taskName;
                existing.targetId = targetId;
                existing.reference.sizeBytes = Files.size(path);
                touch(existing);
                return copy(existing.reference);
            }
        }
        String name = safeName(path.getFileName().toString());
        String type = name.toLowerCase(Locale.ROOT).endsWith(".csv") ? "text/csv" : "application/octet-stream";
        Record record = record(path, name, type, false);
        record.taskName = taskName;
        record.targetId = targetId;
        save(record);
        records.put(record.reference.fileId, record);
        return copy(record.reference);
    }

    /** 按 ID 获取活动租约；在使用前重新检查真实路径，阻止登记之后替换为越界链接。 */
    public synchronized Lease acquire(String id) throws IOException {
        Record record = records.get(id);
        if (record == null) {
            throw missing();
        }
        Path path;
        try {
            path = checked(rootOf(record), Paths.get(record.reference.serverPath));
        } catch (java.nio.file.NoSuchFileException ex) {
            records.remove(id);
            Files.deleteIfExists(metadataPath(id));
            throw missing();
        }
        if (Files.size(path) > config.maxBytes) {
            throw new ManagedFileException(413, "FILE_TOO_LARGE", "文件超过配置字节上限");
        }
        record.reference.sizeBytes = Files.size(path);
        touch(record);
        active.put(id, active.containsKey(id) ? active.get(id) + 1 : 1);
        return new Lease(id, path, copy(record.reference));
    }

    /**
     * 旧路径字段若指向已登记实体则自动持有租约；未登记旧本地路径保留原访问语义。
     * 与原文件解析器一致，查找前去掉位置串两侧空白，避免 Windows 非法路径或漏掉活动保护；
     * 只规范化实际读取位置，调用方保存的原 sourceLocation 仍用于兼容输出回显。
     */
    public synchronized Lease acquirePath(String location) throws IOException {
        Path path = Paths.get(location.trim()).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            path = path.toRealPath();
        }
        for (Record record : records.values()) {
            if (Paths.get(record.reference.serverPath).equals(path)) {
                return acquire(record.reference.fileId);
            }
        }
        return null;
    }

    /** 每次远程解析独占一个配置 temp 下的目录；正在下载时同样受活动保护。 */
    public synchronized TemporaryWorkspace temporaryWorkspace() throws IOException {
        Path directory = Files.createTempDirectory(config.temp, "scope-");
        activeWorkspaces.add(directory);
        return new TemporaryWorkspace(directory);
    }

    /**
     * 为缺省 Web 输出分配独占目录；显式旧目录不由本服务创建或拥有，空值按原拟合器回退为当前目录。
     * 通过最近存在祖先的真实路径解析 Windows 短名/Junction，保证写入期间 TTL 识别的是相同目录。
     */
    public synchronized ResultWorkspace resultWorkspace(String outputDir, boolean specified) throws IOException {
        Path directory;
        if (!specified) {
            directory = Files.createTempDirectory(config.results, "fit-");
            try {
                // 零字节所有权标记只由独占目录分配产生；重启后仍可区分本服务遗留与同名前缀的显式目录。
                Files.createFile(resultOwnershipPath(directory));
            } catch (IOException ex) {
                deleteResultWorkspace(directory);
                throw ex;
            }
        } else {
            String location = outputDir == null || outputDir.trim().isEmpty() ? "." : outputDir.trim();
            directory = Paths.get(location).toAbsolutePath().normalize();
            Path ancestor = directory;
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && ancestor.getParent() != null) {
                ancestor = ancestor.getParent();
            }
            directory = ancestor.toRealPath().resolve(ancestor.relativize(directory));
            relinquishResultOwnership(directory);
        }
        ResultWorkspace workspace = new ResultWorkspace(directory, !specified);
        activeResults.add(workspace);
        return workspace;
    }

    /**
     * 调用者显式复用某个独占目录或其子目录后，该目录可能含调用者新增文件，永久取消整目录回收资格。
     * 已登记结果仍按单文件租约/TTL 处理；同时取消尚活动拥有者的失败回滚，避免并发显式使用被误删。
     */
    private void relinquishResultOwnership(Path directory) throws IOException {
        if (directory.startsWith(config.results) && !directory.equals(config.results)) {
            Path ownedRoot = config.results.resolve(config.results.relativize(directory).getName(0));
            Files.deleteIfExists(resultOwnershipPath(ownedRoot));
            for (ResultWorkspace workspace : activeResults) {
                if (workspace.directory.equals(ownedRoot)) {
                    workspace.owned = false;
                }
            }
        }
    }

    private boolean resultIsActive(Path path) {
        for (ResultWorkspace workspace : activeResults) {
            if (path.startsWith(workspace.directory)) {
                return true;
            }
        }
        return false;
    }

    /** 周期清理已过期且无活动引用的文件，删除实体成功后再注销 ID；异常留待下轮重试。 */
    @Scheduled(fixedDelayString = "${ballistic.files.cleanup-interval-millis:60000}")
    public synchronized void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - config.ttlMillis;
        for (Record record : new java.util.ArrayList<Record>(records.values())) {
            String id = record.reference.fileId;
            if (record.lastAccessMillis > cutoff || active.containsKey(id)
                    // 显式祖先输出目录可包含 uploads，但未读取的上传只能由自身租约/最后使用时间保留。
                    || (!record.upload && resultIsActive(Paths.get(record.reference.serverPath)))) {
                continue;
            }
            try {
                Path path = Paths.get(record.reference.serverPath);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(checked(rootOf(record), path));
                }
                Files.deleteIfExists(metadataPath(id));
                records.remove(id);
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("过期文件清理失败，fileId={}，原因：{}", id, ex.getMessage());
            }
        }
        // 崩溃遗留 scope 不在活动集合，超过 TTL 后回收。Servlet multipart 由容器维护，不在这里扫描。
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(config.temp, "scope-*")) {
            for (Path directory : directories) {
                if (!activeWorkspaces.contains(directory)
                        && Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toMillis() <= cutoff) {
                    deleteWorkspace(directory);
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("临时解析目录清理失败：{}", ex.getMessage());
        }
        cleanupUnregisteredUploads(cutoff);
        cleanupResultWorkspaces(cutoff);
    }

    /** 仅凭元数据所有权标记回收独占目录；前缀不是所有权凭证，显式目录即使叫 fit-* 也不会被扫描。 */
    private void cleanupResultWorkspaces(long cutoff) {
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(config.metadata, "fit-*.workspace")) {
            for (Path marker : markers) {
                checked(config.metadata, marker);
                String name = marker.getFileName().toString();
                Path directory = config.results.resolve(name.substring(0, name.length() - ".workspace".length()));
                boolean retained = resultIsActive(directory);
                for (Record record : records.values()) {
                    retained |= Paths.get(record.reference.serverPath).startsWith(directory);
                }
                if (!retained) {
                    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                        Files.delete(marker);
                    } else if (Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toMillis() <= cutoff) {
                        deleteResultWorkspace(directory);
                        Files.delete(marker);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("结果工作目录清理失败：{}", ex.getMessage());
        }
    }

    private Path resultOwnershipPath(Path directory) {
        return config.metadata.resolve(directory.getFileName().toString() + ".workspace");
    }

    /** 同临时下载目录使用逐层真实路径核验，不沿 NTFS Junction 或符号链接递归删除。 */
    private void deleteResultWorkspace(Path directory) throws IOException {
        if (!directory.toAbsolutePath().normalize().getParent().equals(config.results)) {
            throw new IOException("结果工作目录不在配置根内");
        }
        deleteWorkspaceEntry(directory);
    }

    /** 上传中途进程退出可能遗留未登记实体；只清理本服务生成且没有登记路径的旧 upload 文件。 */
    private void cleanupUnregisteredUploads(long cutoff) {
        java.util.Set<Path> registered = new java.util.HashSet<Path>();
        for (Record record : records.values()) {
            registered.add(Paths.get(record.reference.serverPath));
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(config.uploads, "upload-*")) {
            for (Path file : files) {
                Path real = checked(config.uploads, file);
                if (!registered.contains(real) && Files.getLastModifiedTime(real).toMillis() <= cutoff) {
                    Files.delete(real);
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("未登记上传文件清理失败：{}", ex.getMessage());
        }
    }

    /**
     * Java 8 在 Windows 上即使未传 FOLLOW_LINKS，Files.walk 仍可能进入 NTFS Junction。
     * 因此逐层打开目录前核验真实路径等于本层字面规范路径，任何重定向目录只删除链接自身。
     * 这也避免指向本作用域祖先的 Junction 形成循环；不递归读取或删除链接目标。
     */
    private void deleteWorkspace(Path directory) throws IOException {
        if (!directory.toAbsolutePath().normalize().getParent().equals(config.temp)) {
            throw new IOException("临时目录不在配置根内");
        }
        deleteWorkspaceEntry(directory);
    }

    private void deleteWorkspaceEntry(Path entry) throws IOException {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                && entry.toRealPath().equals(entry.toAbsolutePath().normalize())) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                for (Path child : children) {
                    deleteWorkspaceEntry(child);
                }
            }
        }
        Files.deleteIfExists(entry);
    }

    private Record record(Path path, String name, String type, boolean upload) throws IOException {
        Record record = new Record();
        record.reference = new FileReference();
        record.reference.fileId = UUID.randomUUID().toString();
        record.reference.originalName = name;
        record.reference.contentType = type;
        record.reference.serverPath = path.toRealPath().toString();
        record.reference.sizeBytes = Files.size(path);
        record.upload = upload;
        record.lastAccessMillis = System.currentTimeMillis();
        return record;
    }

    private void touch(Record record) throws IOException {
        record.lastAccessMillis = System.currentTimeMillis();
        save(record);
    }

    /** 同目录临时文件+原子替换，禁止半份 JSON 被下一次启动读取。 */
    private void save(Record record) throws IOException {
        Path pending = Files.createTempFile(config.metadata, "pending-", ".tmp");
        try {
            mapper.writeValue(pending.toFile(), record);
            Files.move(pending, metadataPath(record.reference.fileId), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private Path metadataPath(String id) {
        // 这里只接受内部生成/启动校验过的 UUID；用户查询 ID 不进入此路径函数。
        if (!id.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("非法登记键");
        }
        return config.metadata.resolve(id + ".json");
    }

    private Path rootOf(Record record) {
        return record.upload ? config.uploads : config.results;
    }

    /** 规范化后按真实路径约束边界，允许 Windows 同一目录的短名别名；目录不作为可下载文件。 */
    public static Path checked(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path real;
        try {
            real = normalized.toRealPath();
        } catch (java.nio.file.NoSuchFileException ex) {
            if (!normalized.startsWith(root)) {
                throw new IllegalArgumentException("文件不在配置管理目录内");
            }
            throw ex;
        }
        // Windows 8.3 短目录名与完整目录名是同一实体，不能仅按字面前缀拒绝合法旧路径。
        if (!real.startsWith(root) || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException("文件真实位置越界或不是普通文件");
        }
        return real;
    }

    private static String safeName(String originalName) {
        if (originalName == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("文件名为空、过长或包含控制字符");
        }
        return name;
    }

    private static String safeContentType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "application/octet-stream";
        }
        if (type.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("非法文件媒体类型");
        }
        return MediaType.parseMediaType(type).toString();
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot) : "";
        return extension.matches("\\.[A-Za-z0-9]{1,10}") ? extension : ".bin";
    }

    private static FileReference copy(FileReference source) {
        FileReference copy = new FileReference();
        copy.fileId = source.fileId;
        copy.originalName = source.originalName;
        copy.sizeBytes = source.sizeBytes;
        copy.contentType = source.contentType;
        copy.serverPath = source.serverPath;
        return copy;
    }

    private static ManagedFileException missing() {
        return new ManagedFileException(404, "FILE_NOT_FOUND", "文件引用不存在或已清理");
    }

    /** 只用于磁盘登记的关联与生命周期字段，不直接暴露在浏览器引用响应中。 */
    public static class Record {
        public FileReference reference;
        public boolean upload;
        public long lastAccessMillis;
        public String purpose;
        public String taskName;
        public String targetId;
    }

    /** 调用方必须 try-with-resources；多个并发使用者全部关闭后才允许 TTL 清理。 */
    public final class Lease implements AutoCloseable {
        public final Path path;
        public final FileReference reference;
        private final String id;
        private boolean closed;

        private Lease(String id, Path path, FileReference reference) {
            this.id = id;
            this.path = path;
            this.reference = reference;
        }

        @Override
        public void close() throws IOException {
            synchronized (BallisticFileStorage.this) {
                if (!closed) {
                    int remaining = active.get(id) - 1;
                    if (remaining == 0) {
                        active.remove(id);
                    } else {
                        active.put(id, remaining);
                    }
                    closed = true;
                    touch(records.get(id));
                }
            }
        }
    }

    /** 临时目录生命周期覆盖下载及解析；失败也关闭，异常删除失败留给后续 TTL 清理重试。 */
    public final class TemporaryWorkspace implements AutoCloseable {
        public final Path directory;
        private boolean closed;

        private TemporaryWorkspace(Path directory) {
            this.directory = directory;
        }

        @Override
        public void close() throws IOException {
            synchronized (BallisticFileStorage.this) {
                if (!closed) {
                    activeWorkspaces.remove(directory);
                    closed = true;
                    deleteWorkspace(directory);
                }
            }
        }
    }

    /**
     * 计算结果作用域：complete 之前退出仅回滚本次独占目录；显式目录及其中调用者文件始终由调用者拥有。
     * 登记和 TTL 保护同在存储锁内，计算本身不持锁；关闭仅刷新目录下结果，上传继续由实际输入/下载租约管理。
     */
    public final class ResultWorkspace implements AutoCloseable {
        public final Path directory;
        private boolean owned;
        private boolean completed;
        private boolean closed;

        private ResultWorkspace(Path directory, boolean owned) {
            this.directory = directory;
            this.owned = owned;
        }

        /** 管理范围内自动登记；显式外部结果保持原计算能力，但不能通过文件辅助接口下载。 */
        public void register(String csvPath, String taskName, String targetId) throws IOException {
            synchronized (BallisticFileStorage.this) {
                Path real = Paths.get(csvPath).toRealPath();
                if (real.startsWith(config.results)) {
                    registerResult(csvPath, taskName, targetId);
                } else if (owned) {
                    throw new IllegalArgumentException("本次结果真实位置越出管理目录");
                }
            }
        }

        /** 只在全部文件登记成功后提交作用域，原 CSV 与路径没有复制或更名。 */
        public void complete() {
            synchronized (BallisticFileStorage.this) {
                completed = true;
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (BallisticFileStorage.this) {
                if (closed) {
                    return;
                }
                try {
                    if (owned && !completed) {
                        deleteResultWorkspace(directory);
                        for (Record record : new java.util.ArrayList<Record>(records.values())) {
                            if (Paths.get(record.reference.serverPath).startsWith(directory)) {
                                Files.deleteIfExists(metadataPath(record.reference.fileId));
                                records.remove(record.reference.fileId);
                            }
                        }
                        Files.deleteIfExists(resultOwnershipPath(directory));
                    } else {
                        for (Record record : records.values()) {
                            if (!record.upload && Paths.get(record.reference.serverPath).startsWith(directory)) {
                                touch(record);
                            }
                        }
                    }
                } finally {
                    closed = true;
                    activeResults.remove(this);
                }
            }
        }
    }
}
