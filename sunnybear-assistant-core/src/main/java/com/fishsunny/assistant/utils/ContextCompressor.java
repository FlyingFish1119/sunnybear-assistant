package com.fishsunny.assistant.utils;

/*
 * @Usage 对话上下文压缩器。
 *
 *        当最近一次请求的真实输入 token（assistant 消息 extension 里的
 *        chat_usage.prompt_tokens）超过用户配置的上限时：
 *          1. 找出「最后一条 user 消息之前」的历史，交给 mission 模型总结成可无缝衔接的摘要；
 *          2. 在同一事务内物理清空该会话全部消息，再以「摘要 + 当前 root 用户消息」为头、
 *             把当前这一轮的消息链重新落库（会话文件目录保留不动）；
 *          3. 替换 ChatRequest 的 messages，让本轮对话带着摘要继续；
 *          4. 推送 ###CONTEXT_COMPRESSED### 让前端重拉消息。
 *
 *        总结前先推送 ###CONTEXT_COMPRESSING###（前端展示「压缩中」卡片，避免 START 后长时间空等）；
 *        若总结失败/结果为空而未真正压缩，则推送 ###CONTEXT_COMPRESS_END### 让前端收尾。
 *
 *        只对核心 chat 会话生效；只认 AI 返回的真实用量，适配器不返回 usage 时不触发；
 *        任何异常都只告警并放弃本次压缩，不影响对话。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatUsage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.UserSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ContextCompressor {

    /** 发给总结模型的历史文本总长度上限（字符），超出时丢弃最早的部分 */
    private static final int SUMMARY_MAX_INPUT_CHARS = 120_000;
    /** 单条普通消息进总结文本时的最长字符数 */
    private static final int SUMMARY_PER_MESSAGE_CHARS = 6_000;
    /** 单条工具消息进总结文本时的最长字符数（工具输出通常冗长，压得更狠） */
    private static final int SUMMARY_TOOL_MESSAGE_CHARS = 1_500;
    /** 保留组内单条工具结果超过该字符数时截断（保留组本身已超限的兜底） */
    private static final int RETAINED_TOOL_TRUNCATE_CHARS = 8_000;
    /** 摘要并入 root 用户消息时加的前缀标记 */
    private static final String SUMMARY_BLOCK_HEADER = "【历史对话摘要｜上下文已压缩】\n";
    /** 消息 extension 里存放本轮用量的键，取其中的 prompt_tokens 作为上下文长度 */
    private static final String USAGE_PROMPT_TOKENS = "prompt_tokens";

    /** 总结模型系统提示词：产出可无缝衔接的场景明确摘要 */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个「对话上下文压缩器」。用户与 AI 助手的对话已积累过长，需要你把它压缩成一份简洁、\
            可无缝衔接的摘要，让助手仅凭这份摘要就能继续后续对话，不丢失关键背景。

            要求：
            1. 明确交代对话场景与目标：用户是谁、在做什么、当前任务/话题是什么。
            2. 提炼已确认的关键事实、结论与决策（含具体的文件路径、参数、命名、数值等硬信息）。
            3. 列出已完成的动作与当前进展、尚未完成或待办的事项。
            4. 保留双方的约定、偏好、约束与禁忌（如指定风格、禁止做法、安全边界）。
            5. 忠于原文，不脑补、不虚构；不确定的信息明确标注「不确定」。
            6. 用简体中文、Markdown 分点输出，信息密度优先，不要寒暄或客套。
            7. 只输出摘要本身，不要任何前言、解释或代码块包裹。
            """;

    private final UserSettings userSettings;
    private final AISettings missionAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ChatMessageService chatMessageService;

    public ContextCompressor(UserSettings userSettings,
                             @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                             ChatHttpHandler chatHttpHandler,
                             ChatMessageService chatMessageService) {
        this.userSettings = userSettings;
        this.missionAISettings = missionAISettings;
        this.chatHttpHandler = chatHttpHandler;
        this.chatMessageService = chatMessageService;
    }

    /**
     * 检查并（必要时）压缩上下文。直接作用于传入的 {@link ChatRequest}：压缩成功后其 messages
     * 会被替换为「system + 摘要用户消息 + 当前这一轮消息链」，调用方可继续本轮对话。
     *
     * @param request     当前请求（会被就地修改）
     * @param chatSession 会话
     * @param session     用于推送刷新信号的总线 WebSocket 会话
     */
    public void maybeCompress(ChatRequest request, ChatSession chatSession, WebSocketSession session) {
        // 是否已向前端推送「压缩中」：推送过就必须在 finally 里收尾，避免前端卡片永久停留
        boolean compressing = false;
        // 是否已成功压缩并推送「已完成」：成功时由前端重拉，无需再推送结束信号
        boolean compressed = false;
        try {
            if (!ChatSession.TYPE_CHAT.equals(chatSession.getType())) {
                return;
            }
            Long limit = userSettings.getContextTokenLimit();
            if (limit == null || limit <= 0) {
                return;
            }
            List<ChatMessage> messages = request.getMessages();
            if (messages == null || messages.size() <= 2) {
                return;
            }
            // 只用真实 usage：最近一条带 chat_usage.prompt_tokens 的 assistant 消息，
            // 其 prompt_tokens 就是那一次请求的真实输入上下文长度。拿不到 usage 就不触发
            Integer contextTokens = latestPromptTokens(messages);
            if (contextTokens == null || contextTokens <= limit) {
                return;
            }

            int start = lastUserIndex(messages);
            if (start <= 1) {
                // 没有可总结的历史（当前这一轮自己就超限）：只能截断保留组里超大的工具结果
                truncateRetainedToolResults(messages, session, chatSession.getId());
                return;
            }

            // 0. 总结要调用模型、耗时较长：先通知前端进入「压缩中」，避免 START 之后长时间空等
            notifyContextCompressing(session, chatSession.getId());
            compressing = true;

            // 1. 总结最后一条 user 之前的历史
            String summary = summarize(messages.subList(1, start));
            if (!StringUtils.hasText(summary)) {
                log.warn("上下文压缩：总结结果为空，跳过本次压缩 [{}]", chatSession.getId());
                return;
            }

            // 2. 从库里取保留组原样副本（内存里的对象可能已被 loadSessionFile 展开成 data URI，不能直接回写）
            List<ChatMessage> retained = new ArrayList<>();
            for (int i = start; i < messages.size(); i++) {
                ChatMessage inMemory = messages.get(i);
                ChatMessage fromDb = StringUtils.hasText(inMemory.getId())
                        ? chatMessageService.findById(inMemory.getId()) : null;
                if (fromDb == null) {
                    log.warn("上下文压缩：保留消息 {} 未在库中找到，使用内存副本", inMemory.getId());
                    fromDb = inMemory;
                }
                retained.add(fromDb);
            }

            // 3. 摘要并入保留组首条 user 作为 root，整体在同一事务内「清库 + 重建」，失败则回滚
            List<ChatMessage> prepared = new ArrayList<>();
            for (int i = 0; i < retained.size(); i++) {
                ChatMessage message = retained.get(i);
                if (i == 0) {
                    prependSummary(message, summary);
                }
                String name = StringUtils.hasText(message.getName()) ? message.getName() : userSettings.getUsername();
                message.makeInsertable(chatSession.getId(), i == 0 ? null : retained.get(i - 1).getId(), name);
                prepared.add(message);
            }
            List<ChatMessage> rebuilt = chatMessageService.replaceSessionMessages(chatSession.getId(), prepared);

            // 4. 用重建后的消息替换请求上下文，继续本轮
            List<ChatMessage> rebuiltMessages = new ArrayList<>();
            rebuiltMessages.add(messages.getFirst());
            rebuiltMessages.addAll(rebuilt);
            request.setMessages(rebuiltMessages);

            // 5. 通知前端重拉消息（纯视觉刷新，不参与断线重放）
            notifyContextCompressed(session, chatSession.getId());
            compressed = true;
            log.info("会话 [{}] 上下文压缩完成：总结 {} 字，保留 {} 条消息",
                    chatSession.getId(), summary.length(), rebuilt.size());
        } catch (Exception e) {
            log.warn("上下文压缩失败，跳过本次压缩: {}", e.getMessage(), e);
        } finally {
            // 推送过「压缩中」但未成功压缩（失败/空摘要/异常）时收尾：前端关闭压缩卡片、补回流式占位
            if (compressing && !compressed) {
                notifyContextCompressEnd(session, chatSession.getId());
            }
        }
    }

    /** 从后往前找最后一条 user 消息下标（跳过第 0 条 system）；找不到返回 -1 */
    private int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 1; i--) {
            if (ChatMessage.ROLE_USER.equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把摘要块前置写入用户消息的文本内容（没有文本内容时新建一条）。
     * <p>说明：压缩链路按「首块 = 正文」的语义顺带改写正文（摘要并入首块文本），
     * 属于压缩自身的行为，不受消息正文编辑语义的约束；附件与追加的展示块在压缩里
     * 不做保留承诺（见 {@code truncateRetainedToolMessages}）。
     */
    private void prependSummary(ChatMessage userMessage, String summary) {
        String block = SUMMARY_BLOCK_HEADER + summary.trim() + "\n\n——————————\n";
        List<MessageContent> contents = userMessage.getContents();
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                String original = textContent.getContent() == null ? "" : textContent.getContent();
                textContent.setContent(block + original);
                return;
            }
        }
        contents.addFirst(new TextContent(block));
    }

    /** 调用 mission 模型，把一段历史压缩成可无缝衔接的摘要；失败或空结果返回 null */
    private String summarize(List<ChatMessage> history) throws Exception {
        String transcript = buildTranscript(history);
        if (!StringUtils.hasText(transcript)) {
            return null;
        }
        String userPrompt = "请把下面这段对话历史压缩成一份可无缝衔接的摘要：\n\n" + transcript;
        ChatRequest summaryRequest = new ChatRequest().quickBuild(SUMMARY_SYSTEM_PROMPT, userPrompt, missionAISettings);
        AtomicReference<String> result = new AtomicReference<>();
        chatHttpHandler.translate(
                new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), missionAISettings.getAdapterName(), summaryRequest),
                new ChatHttpHandler.TranslateHandler(null, (translateResult, lastRes) -> {
                    if (translateResult != null && StringUtils.hasText(translateResult.content())) {
                        result.set(translateResult.content().trim());
                    }
                }),
                new ChatHttpHandler.TranslateOption().setStream(missionAISettings.getStream()));
        return result.get();
    }

    /**
     * 把历史拼成总结模型可读的文本：逐条标注角色/名字，工具消息压得更短，
     * 总长超上限时从最早的部分开始丢弃（保留更近的上下文）。
     */
    private String buildTranscript(List<ChatMessage> history) {
        List<String> blocks = new ArrayList<>();
        for (ChatMessage message : history) {
            String role = message.getRole();
            String label = switch (role) {
                case ChatMessage.ROLE_USER -> "用户";
                case ChatMessage.ROLE_ASSISTANT -> "助手";
                case ChatMessage.ROLE_TOOL -> "工具";
                case null, default -> role;
            };
            StringBuilder block = new StringBuilder("[").append(label).append("] ");
            if (StringUtils.hasText(message.getName()) && !ChatMessage.ROLE_USER.equals(role)) {
                block.append(message.getName()).append("：");
            }
            int cap = ChatMessage.ROLE_TOOL.equals(role) ? SUMMARY_TOOL_MESSAGE_CHARS : SUMMARY_PER_MESSAGE_CHARS;
            block.append(truncate(message.resolveText(), cap));
            if (!CollectionUtils.isEmpty(message.getToolCalls())) {
                block.append("（调用工具：");
                for (int i = 0; i < message.getToolCalls().size(); i++) {
                    if (i > 0) {
                        block.append("、");
                    }
                    block.append(message.getToolCalls().get(i).getName());
                }
                block.append("）");
            }
            blocks.add(block.toString());
        }
        // 从最近往前累计，超长时丢弃更早的内容；最后再翻回时间顺序
        Deque<String> kept = new ArrayDeque<>();
        int total = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String block = blocks.get(i);
            if (total + block.length() > SUMMARY_MAX_INPUT_CHARS && !kept.isEmpty()) {
                break;
            }
            kept.addFirst(block);
            total += block.length();
        }
        return String.join("\n\n", kept);
    }

    /**
     * 从后往前找最近一条带真实用量的 assistant 消息，取其 prompt_tokens（该次请求的输入上下文长度）。
     *
     * @return prompt_tokens；没有带用量的 assistant 消息时返回 null
     */
    private Integer latestPromptTokens(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!ChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                continue;
            }
            Object usage = message.getExtension().get(ChatUsage.MESSAGE_KEY);
            if (usage instanceof Map<?, ?> map && map.get(USAGE_PROMPT_TOKENS) instanceof Number prompt) {
                return prompt.intValue();
            }
        }
        return null;
    }

    /**
     * 保留组本身超过上限时的兜底：截断超长（&gt; {@link #RETAINED_TOOL_TRUNCATE_CHARS} 字）的工具结果，
     * 含多模态内容一并丢弃，并同步落库；截断后是否已降到阈值下由下一次真实 usage 继续判断。
     */
    private void truncateRetainedToolResults(List<ChatMessage> messages, WebSocketSession session, String sessionId) {
        boolean changed = false;
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!ChatMessage.ROLE_TOOL.equals(message.getRole())) {
                continue;
            }
            String text = message.resolveText();
            if (text.length() <= RETAINED_TOOL_TRUNCATE_CHARS) {
                continue;
            }
            String truncated = truncate(text, RETAINED_TOOL_TRUNCATE_CHARS);
            // 压缩策略：工具结果超长时整体重建为单块，附件不做保留承诺（与正文编辑语义无关）
            message.setContents(new ArrayList<>(List.of(new TextContent(truncated))));
            changed = true;
            try {
                ChatMessage fromDb = chatMessageService.findById(message.getId());
                if (fromDb != null) {
                    fromDb.setContents(new ArrayList<>(List.of(new TextContent(truncated))));
                    chatMessageService.update(fromDb);
                }
            } catch (Exception e) {
                log.warn("截断工具结果落库失败 [{}]: {}", message.getId(), e.getMessage());
            }
        }
        if (changed) {
            notifyContextCompressed(session, sessionId);
            log.info("会话 [{}] 保留组超限，已截断超大工具结果", sessionId);
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…（内容过长已截断）";
    }

    private void notifyContextCompressing(WebSocketSession session, String sessionId) {
        try {
            session.sendMessage(new TextMessage(ControlSign.SIGN_CONTEXT_COMPRESSING + sessionId));
        } catch (Exception e) {
            log.warn("推送上下文压缩开始信号失败: {}", e.getMessage());
        }
    }

    private void notifyContextCompressed(WebSocketSession session, String sessionId) {
        try {
            session.sendMessage(new TextMessage(ControlSign.SIGN_CONTEXT_COMPRESSED + sessionId));
        } catch (Exception e) {
            log.warn("推送上下文压缩信号失败: {}", e.getMessage());
        }
    }

    private void notifyContextCompressEnd(WebSocketSession session, String sessionId) {
        try {
            session.sendMessage(new TextMessage(ControlSign.SIGN_CONTEXT_COMPRESS_END + sessionId));
        } catch (Exception e) {
            log.warn("推送上下文压缩结束信号失败: {}", e.getMessage());
        }
    }
}
