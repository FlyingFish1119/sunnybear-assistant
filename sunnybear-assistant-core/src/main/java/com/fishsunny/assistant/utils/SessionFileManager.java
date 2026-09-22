package com.fishsunny.assistant.utils;

/*
 * @Usage 会话文件读写统一入口。
 *        落盘位置固定为 {basePath}/session/{sessionId}/file，basePath 取自 AssistantPathConfig，
 *        缺省回落到 user.dir，因此整个目录随项目目录移动。
 *
 *        对外暴露的「引用」形如 "sessionId:fileName"，不含任何磁盘路径，
 *        真实路径在读取时按当前 basePath 现算——这样项目目录搬迁后存量消息依然可读。
 *        解析同时兼容历史数据里的绝对/相对文件系统路径。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/11 19:02
 */

import com.fishsunny.assistant.config.AssistantPathConfig;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class SessionFileManager {

    private static final String SESSION_DIR = "session";
    private static final String FILE_DIR = "file";

    /** 文本读取上限 2MB：超过这个大小的文件不往编辑器里塞，免得把页面拖死 */
    private static final long MAX_TEXT_READ_BYTES = 2 * 1024 * 1024;

    private final String basePath;

    public SessionFileManager(AssistantPathConfig assistantPathConfig) {
        if (assistantPathConfig == null || !StringUtils.hasText(assistantPathConfig.getFileBasePath())) {
            this.basePath = System.getProperty("user.dir");
        } else {
            this.basePath = assistantPathConfig.getFileBasePath();
        }
    }

    /** 会话根目录：{basePath}/session/{sessionId} */
    public Path buildSessionRootPath(String sessionId) {
        requireSessionId(sessionId);
        return Path.of(basePath, SESSION_DIR, sessionId);
    }

    /** 会话文件目录：{basePath}/session/{sessionId}/file */
    public Path buildSessionDirPath(String sessionId) {
        return buildSessionRootPath(sessionId).resolve(FILE_DIR);
    }

    /* ======================== 沙箱路径解析 ======================== */

    /**
     * 把相对路径解析到指定基目录之内 —— 会话文件所有读/写/改名/删除的唯一入口。
     * <p>规则：不允许绝对路径、盘符路径、以分隔符开头，也不允许用 {@code ..} 跳出基目录。
     * 与 {@code FileWriteTool} 的 sessionFile 模式同源（那边已改为调用本方法）。
     *
     * @param baseDir      基目录
     * @param relativePath 相对路径，空串表示基目录自身
     * @return 归一化后的绝对路径，保证落在 baseDir 之内
     */
    public static Path resolveUnder(Path baseDir, String relativePath) {
        Path base = baseDir.toAbsolutePath().normalize();
        String relative = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("路径不能包含盘符: " + relativePath);
        }
        Path resolved = relative.isEmpty()
                ? base
                : base.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("路径不能跳出会话文件目录（禁止 .. 回溯）: " + relativePath);
        }
        return resolved;
    }

    /** 会话沙箱内解析：结果必落在 {basePath}/session/{sessionId}/file 之下 */
    public Path resolveSessionFilePath(String sessionId, String relativePath) {
        return resolveUnder(buildSessionDirPath(sessionId), relativePath);
    }

    /** 文件名消毒：只取最后一段（防 ../ 与盘符混入），过滤 Windows 非法字符。上传落盘共用 */
    public static String sanitizeFileName(String name) {
        String normalized = name == null ? "" : name.trim().replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String last = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        String cleaned = last.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(cleaned) ? cleaned : "file";
    }

    /** 同名自动加序号：img.png → img_1.png → img_2.png。上传落盘共用 */
    public static Path uniquePath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            Path next = dir.resolve(base + "_" + i + ext);
            if (!Files.exists(next)) {
                return next;
            }
        }
        return dir.resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    /**
     * 取得会话内某个文件的真实路径，并确保其所在目录已创建。
     * <p>供需要流式写入的场景使用（如后台命令/脚本日志边跑边写），
     * 这类场景无法预先凑齐完整 byte[] 再交给 {@link #writeSessionFile}。
     *
     * @return 文件的绝对真实路径
     */
    public Path prepareSessionFile(String sessionId, String fileName) throws IOException {
        requireFileName(fileName);
        Path filePath = buildSessionDirPath(sessionId).resolve(fileName);
        Files.createDirectories(filePath.getParent());
        return filePath;
    }

    /**
     * 写入会话文件。
     *
     * @return 可移植引用 "sessionId:fileName"，供持久化到消息/DB
     */
    public String writeSessionFile(String sessionId, String fileName, byte[] data) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("File data cannot be null");
        }
        Path filePath = prepareSessionFile(sessionId, fileName);
        Files.write(filePath, data);
        return buildRef(sessionId, fileName);
    }

    /** 拼接可移植引用 */
    public String buildRef(String sessionId, String fileName) {
        requireSessionId(sessionId);
        requireFileName(fileName);
        return sessionId + ":" + fileName;
    }

    /**
     * 把引用解析为真实文件路径。
     * <p>非引用形态的入参按文件系统路径处理（兼容历史数据）。
     */
    public Path resolveRef(String ref) {
        if (!StringUtils.hasText(ref)) {
            throw new IllegalArgumentException("File reference cannot be empty");
        }
        int idx = ref.indexOf(':');
        if (idx > 0) {
            String rest = ref.substring(idx + 1);
            // 引用形如 {sessionId}:{相对路径}，允许子目录（sub/a.png）。
            // rest 以分隔符开头的不算引用 —— 挡掉 Windows 盘符路径（"E:\data\.." 的 rest 以 \ 开头），
            // 否则 "E:" 会被误判成 sessionId，导致历史绝对路径全部解析失败。
            // 含 .. 回溯的也不算引用，交给 Path.of 按文件系统路径处理（历史数据兜底）。
            if (!rest.isEmpty()
                    && !rest.startsWith("\\") && !rest.startsWith("/")
                    && !rest.contains("..")) {
                return resolveUnder(buildSessionDirPath(ref.substring(0, idx)), rest);
            }
        }
        return Path.of(ref);
    }

    /**
     * 读取会话文件。
     * <p>引用自带 sessionId，历史数据里的路径也自带完整目录，因此无需外部再传会话信息。
     *
     * @param path 可移植引用，或历史数据里的文件系统路径
     * @return 文件内容，文件不存在时返回 null
     */
    @Nullable
    public byte[] readSessionFile(String path) throws IOException {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("File path cannot be empty");
        }
        Path filePath = resolveRef(path);
        File file = filePath.toFile();
        if (!file.exists()) {
            return null;
        }
        return Files.readAllBytes(filePath);
    }

    /* ======================== 会话文件管理（REST / 工具共用） ======================== */

    /**
     * 列出会话文件目录下某一层的条目（不递归）：目录在前，同类型按名称排序。
     * <p>目录不存在时返回空列表 —— 前端第一次打开资源栏时不该看到报错。
     *
     * @param relativeDir 相对会话文件目录的子目录，空串表示根
     */
    public List<SessionFileEntry> listSessionFiles(String sessionId, String relativeDir) throws IOException {
        Path dir = resolveSessionFilePath(sessionId, relativeDir);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<SessionFileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                    boolean isDir = attrs.isDirectory();
                    entries.add(new SessionFileEntry(
                            child.getFileName().toString(),
                            relativePathOf(sessionId, child),
                            isDir,
                            isDir ? -1L : attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                    ));
                } catch (IOException e) {
                    log.warn("读取文件属性失败: {}", child, e);
                }
            }
        }
        entries.sort(Comparator
                .comparing(SessionFileEntry::directory, Comparator.reverseOrder())
                .thenComparing(SessionFileEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * 读取文本内容（UTF-8）。
     * <p>超过 {@link #MAX_TEXT_READ_BYTES} 直接抛异常，由上层转成提示 —— 编辑器吃不下超大文件。
     *
     * @return 文件不存在时返回 null
     */
    @Nullable
    public String readSessionText(String sessionId, String relativePath) throws IOException {
        Path filePath = resolveSessionFilePath(sessionId, relativePath);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        long size = Files.size(filePath);
        if (size > MAX_TEXT_READ_BYTES) {
            throw new IllegalStateException("文件大小 " + (size / 1024) + "KB 超过编辑器上限 "
                    + (MAX_TEXT_READ_BYTES / 1024) + "KB，请下载后查看");
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * 读取文件原始字节（图片预览、二进制下载用）。
     * <p>路径同样走会话沙箱解析；文件不存在返回 null。
     */
    @Nullable
    public byte[] readSessionBytes(String sessionId, String relativePath) throws IOException {
        Path filePath = resolveSessionFilePath(sessionId, relativePath);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        return Files.readAllBytes(filePath);
    }

    /** 写回文本（整文件覆盖），父目录不存在时自动创建。返回可移植引用 */
    public String writeSessionText(String sessionId, String relativePath, String content) throws IOException {
        Path filePath = resolveSessionFilePath(sessionId, relativePath);
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("目标是一个目录，不能写入: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
        return buildRef(sessionId, relativePathOf(sessionId, filePath));
    }

    /**
     * 上传文件落盘：文件名消毒 + 同名自动加序号（img.png → img_1.png），
     * 返回落盘后的相对路径（供前端刷新树定位）。与核心文件上传同一套口径。
     *
     * @param relativeDir 目标子目录，空串表示根
     */
    public String saveSessionUpload(String sessionId, String relativeDir, String originalFilename, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("File data cannot be empty");
        }
        Path dir = resolveSessionFilePath(sessionId, relativeDir);
        String safeName = sanitizeFileName(originalFilename);
        Path target = uniquePath(dir, safeName);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return relativePathOf(sessionId, target);
    }

    /** 新建文件。目标已存在时抛异常，避免把已有内容覆盖掉 */
    public void createSessionText(String sessionId, String relativePath, String content) throws IOException {
        Path filePath = resolveSessionFilePath(sessionId, relativePath);
        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("同名文件已存在: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /** 改名 / 移动（同目录或跨子目录都走这里）。源不存在、目标已存在都会抛异常 */
    public void renameSessionFile(String sessionId, String fromPath, String toPath) throws IOException {
        Path source = resolveSessionFilePath(sessionId, fromPath);
        Path target = resolveSessionFilePath(sessionId, toPath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("源文件不存在: " + fromPath);
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标已存在: " + toPath);
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target);
    }

    /**
     * 删除文件或空目录。
     * <p>只允许删空目录：手滑删掉一整个目录的代价太大，非空目录先让用户自己清空。
     */
    public void deleteSessionFile(String sessionId, String relativePath) throws IOException {
        Path rootDir = buildSessionDirPath(sessionId).toAbsolutePath().normalize();
        Path path = resolveSessionFilePath(sessionId, relativePath);
        if (path.equals(rootDir)) {
            throw new IllegalArgumentException("不能删除会话文件根目录");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("目录非空，请先删除其中的文件: " + relativePath);
                }
            }
        }
        Files.delete(path);
    }

    /** 文件相对会话文件目录的路径（统一 / 分隔，供前端做树形 key） */
    private String relativePathOf(String sessionId, Path child) {
        return buildSessionDirPath(sessionId).toAbsolutePath().normalize()
                .relativize(child.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /** 会话文件条目（列表接口与 session_file_tool 共用） */
    public record SessionFileEntry(
            String name,
            String path,
            boolean directory,
            long size,
            long lastModified
    ) {
    }

    /**
     * 删除会话的整个文件目录（{basePath}/session/{sessionId}）。
     * <p>会话被删除时调用；目录不存在视为已完成。
     */
    public void deleteSessionDir(String sessionId) {
        Path rootPath = buildSessionRootPath(sessionId);
        if (!Files.exists(rootPath)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("删除会话文件失败: {}", path, e);
                }
            });
            log.info("已删除会话文件目录: {}", rootPath);
        } catch (IOException e) {
            log.warn("删除会话文件目录失败 [{}]: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 把消息内容里的文件引用展开为 data URI（图片/音视频）或文本（可读文件），
     * 供发送给模型前调用。已是 data URI 的内容直接透传。
     */
    public List<ChatMessage> loadSessionFile(List<ChatMessage> messages) {
        List<ChatMessage> afterLoadFileMessage = new ArrayList<>();
        for (ChatMessage message : messages) {
            List<MessageContent> contents = readAndReplaceContentFile(message.getContents());
            message.setContents(contents);
            afterLoadFileMessage.add(message);
        }
        return afterLoadFileMessage;
    }

    private List<MessageContent> readAndReplaceContentFile(List<MessageContent> contents) {
        if (contents == null) {
            return new ArrayList<>();
        }
        List<MessageContent> messageContents = new ArrayList<>();
        for (MessageContent content: contents) {
            if (content instanceof TextContent textContent) {
                messageContents.add(new TextContent(textContent.getContent()));
                continue;
            }
            if (content instanceof ImageContent imageContent) {
                String url = imageContent.getUrl();
                // 已是 data URI（如多模态 tool 结果经同轮复用），直接透传，无需读取本地文件
                if (url.startsWith("data:")) {
                    messageContents.add(new ImageContent(url));
                    continue;
                }
                try {
                    byte[] bytes = readSessionFile(url);
                    if (bytes == null) {
                        log.warn("Image file not found: {}", url);
                        continue;
                    }
                    String dataUrl = ObjectUtils.encodeToDataUrl(url, bytes);
                    messageContents.add(new ImageContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading image file: {}", url, e);
                }
            }
            if (content instanceof VideoContent videoContent) {
                String url = videoContent.getUrl();
                if (url.startsWith("data:")) {
                    messageContents.add(new VideoContent(url));
                    continue;
                }
                try {
                    byte[] bytes = readSessionFile(url);
                    if (bytes == null) {
                        log.warn("Video file not found: {}", url);
                        continue;
                    }
                    String dataUrl = ObjectUtils.encodeToDataUrl(url, bytes);
                    messageContents.add(new VideoContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading video file: {}", url, e);
                }
            }
            if (content instanceof AudioContent audioContent) {
                String url = audioContent.getUrl();
                if (url.startsWith("data:")) {
                    messageContents.add(new AudioContent(url));
                    continue;
                }
                try {
                    byte[] bytes = readSessionFile(url);
                    if (bytes == null) {
                        log.warn("Audio file not found: {}", url);
                        continue;
                    }
                    String dataUrl = ObjectUtils.encodeToDataUrl(url, bytes);
                    messageContents.add(new AudioContent(dataUrl));
                } catch (Exception e) {
                    log.error("Error loading audio file: {}", url, e);
                }
            }
            if (content instanceof FileContent fileContent) {
                String path = fileContent.getUrl();
                // 先判可读性再读文件：不可读的类型（图片/压缩包等）无需触碰磁盘，
                // 且文件缺失时仍要给出占位说明，避免用户上传的附件被静默吞掉
                if (!ObjectUtils.canHumanReadFile(path)) {
                    messageContents.add(new TextContent("用户上传了文件：" + path));
                    continue;
                }
                try {
                    byte[] bytes = readSessionFile(path);
                    if (bytes == null) {
                        log.warn("File not found: {}", path);
                        continue;
                    }
                    messageContents.add(new TextContent(new String(bytes, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    log.error("Error loading file: {}", path, e);
                }
            }
        }
        return messageContents;
    }

    private void requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("Session ID cannot be empty");
        }
    }

    private void requireFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("File name cannot be empty");
        }
    }
}
