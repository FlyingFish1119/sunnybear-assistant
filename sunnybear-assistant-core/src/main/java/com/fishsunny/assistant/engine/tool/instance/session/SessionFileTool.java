package com.fishsunny.assistant.engine.tool.instance.session;

/*
 * @Usage Session 文件工具 —— 列出当前 session 文件目录下所有文件的元信息
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/7
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.instance.SessionToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Session 文件列表工具
 * 列出当前会话文件目录（{basePath}/{sessionId}/file）下所有文件的元信息，
 * 包括文件名、大小、修改时间等。
 */
@ToolKitComponent(SessionToolKit.class)
@ConditionalOnExpression("${engine.tool.session.enable:true} && ${engine.tool.session.session-file.enable:true}")
public class SessionFileTool implements ToolHandler {

    public static final String NAME = "session_file_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Value("${assistant.file.base-path:}")
    private String basePath;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public SessionFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("列出当前会话文件目录下的所有文件（含文件名、大小、修改时间）。")
                .setRequired(List.of())
                .setParameters(List.of());
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        // 从上下文获取会话信息
        String sessionId = null;
        if (context.get("chatSession") instanceof ChatSession chatSession) {
            sessionId = chatSession.getId();
        }

        if (!StringUtils.hasText(sessionId)) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话 ID，请检查上下文配置");
        }

        // 构建会话文件目录路径
        String resolvedBasePath = basePath;
        if (!StringUtils.hasText(resolvedBasePath)) {
            resolvedBasePath = System.getProperty("user.dir") + "/session";
        }
        Path sessionFileDir = Paths.get(resolvedBasePath, sessionId, "file");

        if (!Files.exists(sessionFileDir)) {
            return new ToolExecutor.ToolExecuteResponse(name(),
                    "会话文件目录不存在: " + sessionFileDir + "\n该会话尚未上传过文件。");
        }
        if (!Files.isDirectory(sessionFileDir)) {
            throw new ToolExecutor.ToolExecuteException("会话文件路径不是目录: " + sessionFileDir);
        }

        try {
            // 收集文件元信息
            List<FileMeta> files = new ArrayList<>();
            try (Stream<Path> stream = Files.list(sessionFileDir)) {
                for (Path child : stream.sorted().toList()) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                        files.add(new FileMeta(
                                child.getFileName().toString(),
                                child.toAbsolutePath().toString(),
                                attrs.isDirectory(),
                                attrs.isDirectory() ? -1 : attrs.size(),
                                attrs.creationTime(),
                                attrs.lastModifiedTime()
                        ));
                    } catch (IOException ignored) {
                        files.add(new FileMeta(
                                child.getFileName().toString(),
                                child.toAbsolutePath().toString(),
                                Files.isDirectory(child),
                                -1,
                                FileTime.from(Instant.EPOCH),
                                FileTime.from(Instant.EPOCH)
                        ));
                    }
                }
            }

            if (files.isEmpty()) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "会话文件目录 [" + sessionFileDir + "] 中暂无文件。");
            }

            // 按修改时间降序排列
            files.sort(Comparator.comparing(FileMeta::lastModifiedTime).reversed());

            // 构建输出
            StringBuilder sb = new StringBuilder();
            sb.append("会话 [").append(sessionId).append("] 的文件目录内容：\n");
            sb.append("路径: ").append(sessionFileDir).append("\n");
            sb.append("共 ").append(files.size()).append(" 个条目\n\n");

            // 计算列宽
            int maxNameLen = "文件名".length();
            int maxSizeLen = "大小".length();
            for (FileMeta f : files) {
                maxNameLen = Math.max(maxNameLen, f.fileName().length());
                maxSizeLen = Math.max(maxSizeLen, f.getSizeDisplay().length());
            }
            maxNameLen = Math.min(maxNameLen, 60);

            // 表头
            String format = "%-" + maxNameLen + "s  %-" + maxSizeLen + "s  %-6s  %s\n";
            sb.append(String.format(format, "文件名", "大小", "类型", "修改时间"));
            sb.append("-".repeat(maxNameLen + maxSizeLen + 42)).append("\n");

            // 文件条目
            for (FileMeta f : files) {
                String displayName = f.fileName();
                if (displayName.length() > 60) {
                    displayName = displayName.substring(0, 57) + "...";
                }
                sb.append(String.format(format,
                        displayName,
                        f.getSizeDisplay(),
                        f.directory() ? "DIR" : "FILE",
                        f.getLastModifiedTime()));
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString().trim());
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("读取会话文件目录失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    // ======================== 内部记录类 ========================

    private record FileMeta(
            String fileName,
            String absolutePath,
            boolean directory,
            long size,
            FileTime creationTime,
            FileTime lastModifiedTime
    ) {
        public String getSizeDisplay() {
            if (directory) return "-";
            if (size < 0) return "?";
            return ToolKit.formatSize(size);
        }

        public String getLastModifiedTime() {
            if (lastModifiedTime == null || lastModifiedTime.toMillis() == 0) {
                return "未知";
            }
            return FORMATTER.format(Instant.ofEpochMilli(lastModifiedTime.toMillis()));
        }
    }
}
