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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class SessionFileManager {

    private static final String SESSION_DIR = "session";
    private static final String FILE_DIR = "file";

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
            // 引用形如 {sessionId}:{fileName}，fileName 不含任何路径分隔符。
            // 该约束同时挡掉 Windows 盘符路径（"E:\data\.." 的 rest 以 \ 开头），
            // 否则 "E:" 会被误判成 sessionId，导致历史绝对路径全部解析失败。
            if (!rest.isEmpty()
                    && !rest.startsWith("\\") && !rest.startsWith("/")
                    && rest.indexOf('/') < 0 && rest.indexOf('\\') < 0) {
                return buildSessionDirPath(ref.substring(0, idx)).resolve(rest);
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
