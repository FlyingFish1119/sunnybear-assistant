package com.fishsunny.assistant.mvc.service.implement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatExportFileData;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.mvc.service.ChatExportService;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话导出实现：拉取会话历史，按格式（markdown / text / json）拼装导出内容。
 * 聊天 / 角色扮演 / 世界群聊共用同一套消息存储，因此该服务对三种会话通用。
 */
@Service
public class ChatExportServiceImplement implements ChatExportService {

    private static final Logger log = LoggerFactory.getLogger(ChatExportServiceImplement.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** UTF-8 BOM，保证 Windows 下文本文件中文不乱码 */
    private static final String BOM = "﻿";

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatExportServiceImplement(ChatSessionService chatSessionService,
                                      ChatMessageService chatMessageService,
                                      ObjectMapper objectMapper) {
        this.chatSessionService = chatSessionService;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatExportFileData export(String sessionId, String format) {
        ChatSession session = chatSessionService.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        List<ChatMessage> messages;
        try {
            messages = chatMessageService.getConversationHistory(sessionId);
        } catch (Exception e) {
            throw new IllegalArgumentException("获取会话历史失败: " + e.getMessage());
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("该会话暂无消息可导出");
        }
        String normalized = format == null ? "markdown" : format.toLowerCase();
        return switch (normalized) {
            case "json" -> buildJson(session, messages);
            case "text", "txt" -> buildText(session, messages);
            default -> buildMarkdown(session, messages);
        };
    }

    /* ==================== Markdown ==================== */

    private ChatExportFileData buildMarkdown(ChatSession session, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(sessionName(session)).append("\n\n");
        sb.append("> 导出时间：").append(now()).append("\n\n");
        sb.append("---\n\n");
        for (ChatMessage msg : messages) {
            appendMarkdownMessage(sb, msg);
        }
        return new ChatExportFileData()
                .setContent(BOM + sb.toString())
                .setExt("md")
                .setMime("text/markdown");
    }

    private void appendMarkdownMessage(StringBuilder sb, ChatMessage msg) {
        if (msg == null) return;
        // 工具消息单独成块
        if (ChatMessage.ROLE_TOOL.equals(msg.getRole())) {
            sb.append("### 工具 · ").append(safe(msg.getName())).append("\n");
            if (msg.getCreateTime() != null) sb.append("> ").append(formatTime(msg.getCreateTime())).append("\n");
            sb.append("\n");
            appendToolContent(sb, msg, true);
            sb.append("---\n\n");
            return;
        }
        String roleName = switch (msg.getRole()) {
            case ChatMessage.ROLE_USER -> "用户";
            case ChatMessage.ROLE_ASSISTANT -> "助手";
            default -> safe(msg.getRole());
        };
        StringBuilder title = new StringBuilder("## ").append(roleName);
        if (StringUtils.hasText(msg.getName())) title.append(" · ").append(msg.getName());
        if (msg.getCreateTime() != null) title.append(" · ").append(formatTime(msg.getCreateTime()));
        sb.append(title).append("\n\n");
        // 思考过程以引用块展示
        if (StringUtils.hasText(msg.getReasoningContent())) {
            sb.append("**思考过程：**\n\n");
            sb.append("> ").append(msg.getReasoningContent().replace("\n", "\n> ")).append("\n\n");
        }
        appendContents(sb, msg.getContents(), true);
        // 工具调用列表
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            sb.append("**工具调用：**\n\n");
            List<String> names = new ArrayList<>();
            for (ChatToolRequest tc : msg.getToolCalls()) {
                names.add("`" + safe(tc.getName()) + "`");
            }
            sb.append(String.join(", ", names)).append("\n\n");
        }
        sb.append("---\n\n");
    }

    /* ==================== 纯文本 ==================== */

    private ChatExportFileData buildText(ChatSession session, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("会话：").append(sessionName(session)).append("\n");
        sb.append("导出时间：").append(now()).append("\n");
        sb.append("==============================\n\n");
        for (ChatMessage msg : messages) {
            appendTextMessage(sb, msg);
        }
        return new ChatExportFileData()
                .setContent(BOM + sb.toString())
                .setExt("txt")
                .setMime("text/plain");
    }

    private void appendTextMessage(StringBuilder sb, ChatMessage msg) {
        if (msg == null) return;
        if (ChatMessage.ROLE_TOOL.equals(msg.getRole())) {
            sb.append("【工具】").append(safe(msg.getName())).append("\n");
            if (msg.getCreateTime() != null) sb.append(formatTime(msg.getCreateTime())).append("\n");
            sb.append("\n");
            appendToolContent(sb, msg, false);
            sb.append("---\n\n");
            return;
        }
        String roleName = switch (msg.getRole()) {
            case ChatMessage.ROLE_USER -> "用户";
            case ChatMessage.ROLE_ASSISTANT -> "助手";
            default -> safe(msg.getRole());
        };
        StringBuilder title = new StringBuilder(roleName);
        if (StringUtils.hasText(msg.getName())) title.append(" · ").append(msg.getName());
        if (msg.getCreateTime() != null) title.append(" · ").append(formatTime(msg.getCreateTime()));
        sb.append(title).append("\n\n");
        if (StringUtils.hasText(msg.getReasoningContent())) {
            sb.append("思考过程：\n");
            sb.append(msg.getReasoningContent()).append("\n\n");
        }
        appendContents(sb, msg.getContents(), false);
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ChatToolRequest tc : msg.getToolCalls()) {
                names.add(safe(tc.getName()));
            }
            sb.append("工具调用：").append(String.join(", ", names)).append("\n\n");
        }
        sb.append("---\n\n");
    }

    /* ==================== JSON ==================== */

    private ChatExportFileData buildJson(ChatSession session, List<ChatMessage> messages) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionName", sessionName(session));
        data.put("exportTime", now());
        data.put("messageCount", messages.size());
        data.put("messages", messages);
        try {
            String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            return new ChatExportFileData()
                    .setContent(content)
                    .setExt("json")
                    .setMime("application/json");
        } catch (Exception e) {
            log.error("导出会话 JSON 序列化失败: sessionId={}", session.getId(), e);
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage());
        }
    }

    /* ==================== 内容块共用 ==================== */

    /**
     * 追加消息内容块（文本/图片/音视频/文件）
     *
     * @param useMd true 使用 Markdown 语法，false 使用纯文本
     */
    private void appendContents(StringBuilder sb, List<MessageContent> contents, boolean useMd) {
        if (contents == null) return;
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                if (textContent.getContent() != null) {
                    sb.append(textContent.getContent()).append("\n\n");
                }
            } else if (content instanceof ImageContent imageContent) {
                if (useMd) {
                    sb.append("![图片](").append(safe(imageContent.getUrl())).append(")\n\n");
                } else {
                    sb.append("[图片] ").append(fileNameOf(imageContent.getUrl())).append("\n\n");
                }
            } else if (content instanceof AudioContent audioContent) {
                if (useMd) {
                    sb.append("[音频：").append(fileNameOf(audioContent.getUrl())).append("](")
                            .append(safe(audioContent.getUrl())).append(")\n\n");
                } else {
                    sb.append("[音频] ").append(fileNameOf(audioContent.getUrl())).append("\n\n");
                }
            } else if (content instanceof VideoContent videoContent) {
                if (useMd) {
                    sb.append("[视频：").append(fileNameOf(videoContent.getUrl())).append("](")
                            .append(safe(videoContent.getUrl())).append(")\n\n");
                } else {
                    sb.append("[视频] ").append(fileNameOf(videoContent.getUrl())).append("\n\n");
                }
            } else if (content instanceof FileContent fileContent) {
                if (useMd) {
                    sb.append("[文件：").append(fileNameOf(fileContent.getUrl())).append("](")
                            .append(safe(fileContent.getUrl())).append(")\n\n");
                } else {
                    sb.append("[文件] ").append(fileNameOf(fileContent.getUrl())).append("\n\n");
                }
            }
        }
    }

    /**
     * 追加工具消息内容（工具结果 content 是 JSON，含 succeed/result）
     *
     * @param useMd true 使用 Markdown 语法，false 使用纯文本
     */
    private void appendToolContent(StringBuilder sb, ChatMessage msg, boolean useMd) {
        if (msg.getContents() == null) return;
        for (MessageContent content : msg.getContents()) {
            if (!(content instanceof TextContent textContent) || !StringUtils.hasText(textContent.getContent())) {
                continue;
            }
            String raw = textContent.getContent();
            Map<?, ?> parsed = null;
            try {
                parsed = objectMapper.readValue(raw, Map.class);
            } catch (Exception e) { /* 非 JSON，按纯文本输出 */ }
            if (parsed != null) {
                boolean succeed = Boolean.TRUE.equals(parsed.get("succeed"));
                String status = succeed ? "✅ 执行成功" : "❌ 执行失败";
                sb.append(useMd ? ("**" + status + "**") : status).append("\n\n");
                Object result = parsed.get("result");
                if (result != null && StringUtils.hasText(String.valueOf(result))) {
                    sb.append(result).append("\n\n");
                }
            } else {
                sb.append(raw).append("\n\n");
            }
        }
    }

    /* ==================== 工具 ==================== */

    private String sessionName(ChatSession session) {
        return StringUtils.hasText(session.getName()) ? session.getName() : "对话记录";
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    private String formatTime(LocalDateTime time) {
        return time.format(TIME_FORMAT);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从路径中提取文件名（去掉目录前缀和时间戳前缀），与前端 $fileUrl.fileName 逻辑一致
     */
    private String fileNameOf(String url) {
        if (!StringUtils.hasText(url)) return "file";
        int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        String name = idx >= 0 ? url.substring(idx + 1) : url;
        return name.replaceFirst("^\\d+_", "");
    }
}
