package com.fishsunny.assistant.engine.tool.instance.session;

/*
 * @Usage Session 文件工具 —— 列出当前 session 文件目录下所有文件的元信息
 *
 *        列目录逻辑已下沉到 SessionFileManager（与前端 /session/file/list 共用一份实现），
 *        本工具只负责把结果排版成给模型看的表格；输出格式与排序规则保持改动前原样。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/7
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.SessionToolKit;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Session 文件列表工具
 * 列出当前会话文件目录（{basePath}/session/{sessionId}/file）下所有文件的元信息，
 * 包括文件名、大小、修改时间等。
 */
@ToolKitComponent(SessionToolKit.class)
@ConditionalOnExpression("${engine.tool.session.enable:true} && ${engine.tool.session.session-file.enable:true}")
public class SessionFileTool implements ToolHandler {

    public static final String NAME = "session_file_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final SessionFileManager sessionFileManager;
    private final ToolRegister register;

    public SessionFileTool(SessionFileManager sessionFileManager) {
        this.sessionFileManager = sessionFileManager;
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("列出当前会话文件目录下的所有文件（含文件名、大小、修改时间）。")
                .setRequired(List.of())
                .setParameters(List.of());
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        // 从上下文获取会话信息
        String sessionId = chatSession.getId();

        if (!StringUtils.hasText(sessionId)) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话 ID，请检查上下文配置");
        }

        // 构建会话文件目录路径
        Path sessionFileDir = sessionFileManager.buildSessionDirPath(sessionId);

        if (!Files.exists(sessionFileDir)) {
            throw new ToolExecutor.ToolExecuteException("会话文件目录不存在: " + sessionFileDir + "\n该会话尚未上传过文件。");
        }
        if (!Files.isDirectory(sessionFileDir)) {
            throw new ToolExecutor.ToolExecuteException("会话文件路径不是目录: " + sessionFileDir);
        }

        try {
            // 列目录逻辑已下沉到 SessionFileManager（与前端 /session/file/list 共用）
            List<SessionFileManager.SessionFileEntry> files = sessionFileManager.listSessionFiles(sessionId, "");

            if (files.isEmpty()) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "会话文件目录 [" + sessionFileDir + "] 中暂无文件。");
            }

            // 本工具的输出仍按修改时间降序，不跟随资源栏那份「目录在前」的排序
            files.sort(Comparator.comparing(SessionFileManager.SessionFileEntry::lastModified).reversed());

            // 构建输出
            StringBuilder sb = new StringBuilder();
            sb.append("会话 [").append(sessionId).append("] 的文件目录内容：\n");
            sb.append("路径: ").append(sessionFileDir).append("\n");
            sb.append("共 ").append(files.size()).append(" 个条目\n\n");

            // 计算列宽
            int maxNameLen = "文件名".length();
            int maxSizeLen = "大小".length();
            for (SessionFileManager.SessionFileEntry f : files) {
                maxNameLen = Math.max(maxNameLen, f.name().length());
                maxSizeLen = Math.max(maxSizeLen, formatSize(f).length());
            }
            maxNameLen = Math.min(maxNameLen, 60);

            // 表头
            String format = "%-" + maxNameLen + "s  %-" + maxSizeLen + "s  %-6s  %s\n";
            sb.append(String.format(format, "文件名", "大小", "类型", "修改时间"));
            sb.repeat("-", maxNameLen + maxSizeLen + 42).append("\n");

            // 文件条目
            for (SessionFileManager.SessionFileEntry f : files) {
                String displayName = f.name();
                if (displayName.length() > 60) {
                    displayName = displayName.substring(0, 57) + "...";
                }
                sb.append(String.format(format,
                        displayName,
                        formatSize(f),
                        f.directory() ? "DIR" : "FILE",
                        formatTime(f)));
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

    // ======================== 格式化辅助 ========================

    private String formatSize(SessionFileManager.SessionFileEntry entry) {
        if (entry.directory()) return "-";
        if (entry.size() < 0) return "?";
        return ToolKit.formatSize(entry.size());
    }

    private String formatTime(SessionFileManager.SessionFileEntry entry) {
        if (entry.lastModified() <= 0) return "未知";
        return FORMATTER.format(Instant.ofEpochMilli(entry.lastModified()));
    }
}
