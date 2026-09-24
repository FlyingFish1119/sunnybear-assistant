package com.fishsunny.assistant.utils;

/*
 * @Usage 分片断点上传管理器。
 *        大文件（几百 MB 起）不再整份塞进一次 multipart 请求，而是切成若干分片分别上传，
 *        全部到齐后由本类流式拼接落盘到会话文件目录或核心文件目录。
 *
 *        暂存结构：{basePath}/.upload-tmp/{uploadId}/
 *          - 00000000.part、00000001.part …  已接收的分片
 *          - meta.json                       本次上传的元信息（目标、文件名、分片数、完成态）
 *
 *        断点：uploadId 由前端按「文件名 + 大小 + 修改时间 + 目标」稳定哈希得出，
 *        刷新 / 失败后重选同一文件仍是同一个 id；status 会返回已收到的分片序号，只补缺口。
 *        合并完成后保留 meta.json（记录 resultPath），使 complete 幂等、status 可答复"已完成"。
 *
 *        全程按 1MB 缓冲流式读写，不把整个文件读进内存。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/25
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.config.AssistantPathConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
public class ChunkedUploadManager {

    /** 暂存根目录名（位于 file base path 下） */
    private static final String STAGING_DIR = ".upload-tmp";

    /** 元信息文件名 */
    private static final String META_FILE = "meta.json";

    /** uploadId 白名单：防止通过 id 拼出路径穿越 */
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 单个分片大小上限（防御性兜底，正常前端 8MB） */
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;

    /** 拼接时的拷贝缓冲 */
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;

    private static final String SCOPE_SESSION = "session";
    private static final String SCOPE_CORE = "core";

    private final String basePath;
    private final ObjectMapper objectMapper;
    private final SessionFileManager sessionFileManager;
    private final CoreFileManager coreFileManager;

    public ChunkedUploadManager(AssistantPathConfig assistantPathConfig,
                                ObjectMapper objectMapper,
                                SessionFileManager sessionFileManager,
                                CoreFileManager coreFileManager) {
        if (assistantPathConfig == null || !StringUtils.hasText(assistantPathConfig.getFileBasePath())) {
            this.basePath = System.getProperty("user.dir");
        } else {
            this.basePath = assistantPathConfig.getFileBasePath();
        }
        this.objectMapper = objectMapper;
        this.sessionFileManager = sessionFileManager;
        this.coreFileManager = coreFileManager;
    }

    /* ======================== 对外操作 ======================== */

    /**
     * 初始化（或恢复）一次分片上传：建立暂存目录、落元信息，返回已收到的分片。
     * <p>已在暂存目录里的分片不会被覆盖 —— 这就是断点续传的基础。
     */
    public UploadStatus init(InitRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String uploadId = requireUploadId(request.getUploadId());
        validateScope(request.getScope(), request.getSessionId());
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (request.getSize() <= 0) {
            throw new IllegalArgumentException("文件大小必须大于 0");
        }
        if (request.getTotalChunks() <= 0) {
            throw new IllegalArgumentException("分片总数必须大于 0");
        }

        Path dir = stagingDir(uploadId);
        Files.createDirectories(dir);
        Meta meta = readMeta(dir);
        // 残留的"已完成"标记（旧版本数据）一律清掉：完成态不再代表可续传，
        // 同一文件重新上传应当重新走一遍，而不是被当成"上次已完成"直接跳过
        if (meta != null && meta.isCompleted()) {
            deleteStagingDir(dir);
            Files.createDirectories(dir);
            meta = null;
        }
        if (meta == null) {
            meta = new Meta();
            meta.setUploadId(uploadId);
            meta.setScope(normalizeScope(request.getScope()));
            meta.setSessionId(request.getSessionId());
            meta.setDir(request.getDir() == null ? "" : request.getDir());
            meta.setName(request.getName());
            meta.setSize(request.getSize());
            meta.setTotalChunks(request.getTotalChunks());
            meta.setCompleted(false);
            writeMeta(dir, meta);
            log.info("分片上传初始化: uploadId={}, name={}, size={}, chunks={}", uploadId, request.getName(),
                    request.getSize(), request.getTotalChunks());
        } else if (!meta.isCompleted() && !sameIdentity(meta, request)) {
            // 同一 uploadId 但描述的不是同一个文件：多为前端哈希碰撞或参数不一致，拒绝以免拼错
            throw new IllegalArgumentException("uploadId 已存在且目标不一致，请刷新后重试");
        }
        return buildStatus(uploadId, meta, dir);
    }

    /** 查询某次上传已收到的分片；暂存目录不存在时返回空结果（exists=false） */
    public UploadStatus status(String uploadId) throws IOException {
        String id = requireUploadId(uploadId);
        Path dir = stagingDir(id);
        Meta meta = readMeta(dir);
        if (meta == null) {
            return new UploadStatus(id, 0, List.of(), false, null, false);
        }
        return buildStatus(id, meta, dir);
    }

    /**
     * 保存一个分片。写入临时文件后原子替换，避免并发读取到半截分片。
     *
     * @return 累计已收到的分片数
     */
    public int saveChunk(String uploadId, int index, byte[] data) throws IOException {
        String id = requireUploadId(uploadId);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("分片内容不能为空");
        }
        if (data.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("分片过大: " + data.length + " 字节");
        }
        Path dir = stagingDir(id);
        Meta meta = readMeta(dir);
        if (meta == null) {
            throw new IllegalArgumentException("上传会话不存在或已过期，请重新开始: " + id);
        }
        if (meta.isCompleted()) {
            throw new IllegalArgumentException("该上传已完成");
        }
        if (index < 0 || index >= meta.getTotalChunks()) {
            throw new IllegalArgumentException("分片序号越界: " + index);
        }
        Path partPath = dir.resolve(partName(index));
        Path tmp = dir.resolve(partName(index) + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, data);
        try {
            Files.move(tmp, partPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // 个别文件系统不支持 ATOMIC_MOVE，退回普通替换
            Files.move(tmp, partPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return listReceivedIndexes(dir, meta.getTotalChunks()).size();
    }

    /**
     * 全部分片到齐后合并落盘：校验完整性 → 流式拼接 → 同名自动加序号 → 删除分片。
     * <p>幂等：已完成的 uploadId 直接返回上次的落盘路径。
     */
    public UploadStatus complete(String uploadId) throws IOException {
        String id = requireUploadId(uploadId);
        Path dir = stagingDir(id);
        Meta meta = readMeta(dir);
        if (meta == null) {
            throw new IllegalArgumentException("上传会话不存在或已过期，请重新开始: " + id);
        }
        if (meta.isCompleted()) {
            return buildStatus(id, meta, dir);
        }
        List<Integer> missing = missingIndexes(dir, meta.getTotalChunks());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("还有 " + missing.size() + " 个分片未上传，无法合并");
        }
        long actualSize = sumPartSizes(dir, meta.getTotalChunks());
        if (actualSize != meta.getSize()) {
            throw new IllegalArgumentException("分片总大小 " + actualSize + " 与声明的 " + meta.getSize() + " 不一致");
        }

        Path targetDir = resolveTargetDir(meta);
        Files.createDirectories(targetDir);
        String safeName = SessionFileManager.sanitizeFileName(meta.getName());
        Path target = SessionFileManager.uniquePath(targetDir, safeName);

        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            for (int i = 0; i < meta.getTotalChunks(); i++) {
                Path part = dir.resolve(partName(i));
                try (InputStream in = Files.newInputStream(part)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            // 合并失败：把可能生成的不完整目标文件清掉，分片保留以便重试
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignore) {
                // 清理失败不影响主异常
            }
            throw e;
        }

        String relativePath = relativePathOf(meta, target);
        // 合并成功即清空暂存目录（含 meta）：完成态不保留，否则同一文件再次上传时
        // 会命中同一个 uploadId、被"已完成"标记挡住而实际不传
        deleteStagingDir(dir);
        log.info("分片上传合并完成: uploadId={}, chunks={}, -> {}", id, meta.getTotalChunks(), relativePath);
        return new UploadStatus(id, meta.getTotalChunks(), allIndexes(meta.getTotalChunks()), true, relativePath, true);
    }

    /** 放弃上传并清空暂存目录 */
    public void abort(String uploadId) throws IOException {
        String id = requireUploadId(uploadId);
        deleteStagingDir(stagingDir(id));
        log.info("分片上传已放弃: uploadId={}", id);
    }

    /** 递归删除整个暂存目录（含 meta / 分片 / 临时文件）；目录不存在视为已完成 */
    private void deleteStagingDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理上传暂存文件失败: {}", p, e);
                }
            });
        }
    }

    /**
     * 清理超过 {@code maxAge} 未活动的暂存目录（完成态或半截都清）。
     * 以目录内文件的最新修改时间为准，正在上传的不会被误删。
     */
    public void cleanupExpired(Duration maxAge) {
        Path root = stagingRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try {
                    Instant lastActive = lastModifiedWithin(dir);
                    if (lastActive.isBefore(cutoff)) {
                        abort(dir.getFileName().toString());
                    }
                } catch (Exception e) {
                    log.warn("清理过期上传目录失败: {}", dir, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描上传暂存目录失败: {}", root, e);
        }
    }

    /* ======================== 路径 / 元信息 ======================== */

    private Path stagingRoot() {
        return Path.of(basePath, STAGING_DIR);
    }

    private Path stagingDir(String uploadId) {
        Path root = stagingRoot().toAbsolutePath().normalize();
        Path dir = root.resolve(uploadId).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("非法的 uploadId: " + uploadId);
        }
        return dir;
    }

    private Meta readMeta(Path dir) {
        Path metaFile = dir.resolve(META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(metaFile.toFile(), Meta.class);
        } catch (Exception e) {
            log.warn("读取上传元信息失败: {}", metaFile, e);
            return null;
        }
    }

    private void writeMeta(Path dir, Meta meta) throws IOException {
        objectMapper.writeValue(dir.resolve(META_FILE).toFile(), meta);
    }

    private Path resolveTargetDir(Meta meta) {
        if (SCOPE_CORE.equals(meta.getScope())) {
            return coreFileManager.resolveCoreFilePath(meta.getDir());
        }
        if (!StringUtils.hasText(meta.getSessionId())) {
            throw new IllegalArgumentException("会话上传缺少 sessionId");
        }
        return sessionFileManager.resolveSessionFilePath(meta.getSessionId(), meta.getDir());
    }

    private String relativePathOf(Meta meta, Path file) {
        Path base = SCOPE_CORE.equals(meta.getScope())
                ? coreFileManager.buildCoreDirPath()
                : sessionFileManager.buildSessionDirPath(meta.getSessionId());
        return base.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private UploadStatus buildStatus(String uploadId, Meta meta, Path dir) throws IOException {
        List<Integer> received = meta.isCompleted()
                ? allIndexes(meta.getTotalChunks())
                : listReceivedIndexes(dir, meta.getTotalChunks());
        return new UploadStatus(uploadId, meta.getTotalChunks(), received,
                meta.isCompleted(), meta.getResultPath(), true);
    }

    private List<Integer> listReceivedIndexes(Path dir, int totalChunks) throws IOException {
        boolean[] present = new boolean[totalChunks];
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.part")) {
                for (Path part : stream) {
                    int idx = parsePartIndex(part.getFileName().toString());
                    if (idx >= 0 && idx < totalChunks) {
                        present[idx] = true;
                    }
                }
            }
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (present[i]) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private List<Integer> missingIndexes(Path dir, int totalChunks) throws IOException {
        List<Integer> received = listReceivedIndexes(dir, totalChunks);
        boolean[] present = new boolean[totalChunks];
        for (int idx : received) {
            present[idx] = true;
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!present[i]) {
                missing.add(i);
            }
        }
        return missing;
    }

    private long sumPartSizes(Path dir, int totalChunks) throws IOException {
        long total = 0;
        for (int i = 0; i < totalChunks; i++) {
            total += Files.size(dir.resolve(partName(i)));
        }
        return total;
    }

    private List<Integer> allIndexes(int totalChunks) {
        List<Integer> indexes = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private int parsePartIndex(String fileName) {
        if (!fileName.endsWith(".part")) {
            return -1;
        }
        try {
            return Integer.parseInt(fileName.substring(0, fileName.length() - ".part".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 分片文件名固定 8 位，保证目录内字典序 = 分片序 */
    private String partName(int index) {
        return String.format("%08d.part", index);
    }

    private Instant lastModifiedWithin(Path dir) throws IOException {
        Instant latest = Files.getLastModifiedTime(dir).toInstant();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList()) {
                Instant t = Files.getLastModifiedTime(p).toInstant();
                if (t.isAfter(latest)) {
                    latest = t;
                }
            }
        }
        return latest;
    }

    private String requireUploadId(String uploadId) {
        if (!StringUtils.hasText(uploadId) || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new IllegalArgumentException("非法的 uploadId: " + uploadId);
        }
        return uploadId;
    }

    private String normalizeScope(String scope) {
        return SCOPE_CORE.equals(scope) ? SCOPE_CORE : SCOPE_SESSION;
    }

    private void validateScope(String scope, String sessionId) {
        String normalized = normalizeScope(scope);
        if (SCOPE_SESSION.equals(normalized) && !StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("会话上传必须提供 sessionId");
        }
    }

    private boolean sameIdentity(Meta meta, InitRequest request) {
        return equalsNullSafe(meta.getScope(), normalizeScope(request.getScope()))
                && equalsNullSafe(meta.getSessionId(), request.getSessionId())
                && equalsNullSafe(meta.getDir(), request.getDir() == null ? "" : request.getDir())
                && equalsNullSafe(meta.getName(), request.getName())
                && meta.getSize() == request.getSize()
                && meta.getTotalChunks() == request.getTotalChunks();
    }

    private boolean equalsNullSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /* ======================== 数据结构 ======================== */

    /** 分片上传请求（init 用） */
    @Data
    public static class InitRequest {

        /** 前端计算的稳定 id（名字 + 大小 + 修改时间 + 目标 的哈希） */
        private String uploadId;

        /** 目标范围：session（默认）| core */
        private String scope;

        /** 会话 ID（scope=session 时必填） */
        private String sessionId;

        /** 目标子目录，空串表示根 */
        private String dir;

        /** 原始文件名 */
        private String name;

        /** 文件总字节数 */
        private long size;

        /** 分片总数 */
        private int totalChunks;
    }

    /** 上传进度 / 结果 */
    public record UploadStatus(
            String uploadId,
            int totalChunks,
            List<Integer> received,
            boolean completed,
            String path,
            boolean exists
    ) {
    }

    /** 暂存元信息（落盘为 meta.json） */
    @Data
    public static class Meta {

        private String uploadId;
        private String scope;
        private String sessionId;
        private String dir;
        private String name;
        private long size;
        private int totalChunks;
        private boolean completed;
        private String resultPath;
    }
}
