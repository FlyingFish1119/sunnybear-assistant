package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage WebSocket 消息业务处理器 —— 串联 消息持久化 + AI 调用 + 流式响应
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 本类主要和元数据处理逻辑有关
 */
@Slf4j
@Component
public class ServiceProcessor {

    /** 会话附件存储文件名的毫秒级时间戳格式 */
    private static final DateTimeFormatter SESSION_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");

    /** 会话标题生成器系统提示词（由 cub AI 承担该任务，prompt 固化在此） */
    private static final String TITLE_PROMPT = """
            你是一个专业的对话标题生成器。你的任务是根据给定的AI系统提示词和用户的第一条消息，为整段对话生成一个简洁、自然的标题。

            要求：
            - 标题长度尽量控制在15个汉字（或10个英文单词）以内。
            - 准确概括对话的核心主题或用户的主要意图，而不是简单重复原话。
            - 不要使用[对话]、[聊天]、[关于]、[求助]这类泛指词，要给出具体信息。
            - 标题语言要与用户输入的语言保持一致。
            - 只输出一个 JSON 对象，格式为 {"title": "标题"}，不要包含 markdown 代码块标记或任何其他文字。
            """;

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;
    private final AssistantSettings assistantSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings cubAISettings;
    private final SessionMessageBus sessionMessageBus;


    public ServiceProcessor(ChatMessageService chatMessageService,
                            ChatSessionService chatSessionService,
                            ObjectMapper objectMapper,
                            AssistantSettings assistantSettings,
                            ChatHttpHandler chatHttpHandler,
                            @Qualifier(AISettings.CUB) AISettings cubAISettings,
                            SessionMessageBus sessionMessageBus
                            ) {
        this.chatMessageService = chatMessageService;
        this.chatSessionService = chatSessionService;
        this.objectMapper = objectMapper;
        this.assistantSettings = assistantSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.cubAISettings = cubAISettings;
        this.sessionMessageBus = sessionMessageBus;
    }

    public void generateTitle(ChatSession chatSession, String userPrompt, String responsePrompt) throws Exception {
        String prompt = """
                请参考下面的内容生成一个会话标题：
                目标 AI 系统提示词：${systemPrompt}。
                用户给这个 AI 发送的消息：${userPrompt}
                AI 回复的信息：${assistantPrompt}
                """;
        prompt = prompt.replace("${systemPrompt}", assistantSettings.getAssistantName());
        prompt = prompt.replace("${userPrompt}", userPrompt);
        prompt = prompt.replace("${assistantPrompt}", responsePrompt);

        ChatRequest request = new ChatRequest().quickJsonBuild(TITLE_PROMPT, prompt, cubAISettings);
        try {
            ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(
                    UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request
            );
            ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler()
                    .complete((result, lastRes) -> chatSession.setName(parseTitle(result.content())));
            ChatHttpHandler.TranslateOption translateOption = new ChatHttpHandler.TranslateOption()
                    .setStream(cubAISettings.getStream())
                    .setEnableTTS(false);

            chatHttpHandler.translate(translateData, translateHandler, translateOption);
            chatSessionService.update(chatSession);
            sessionMessageBus.publish(chatSession.getId(), ControlSign.UPDATE_SESSION + objectMapper.writeValueAsString(chatSession));
        } catch (Exception e) {
            log.error("Error title generate: {}", e.getMessage());
        }
    }

    /**
     * 从 cub 返回的 JSON 中解析标题字段，期望格式：{"title": "标题"}。
     * 容错处理 ```json 代码块包裹，解析失败时回退为原始文本。
     */
    private String parseTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String json = raw.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            String title = parsed.get("title");
            if (StringUtils.hasText(title)) {
                return title.trim();
            }
        } catch (Exception e) {
            log.warn("解析标题 JSON 失败，回退为原始文本: {}", e.getMessage());
        }
        return raw.trim();
    }

    /**
     * replace 失败回滚：把 replace 停用的旧助手分支重新点亮。
     * <p>
     * 停用是落库动作，一旦本轮生成失败（建连报错/超时/流中断），活跃链就永远断在父消息上 ——
     * 父消息是 tool 时链尾变成 tool，用户后续的消息会顺势挂到它后面，坏状态就此固化。
     * 这里把状态修回去，让「重开这一轮」失败后表现得像什么都没发生过。
     * <p>
     * 仅在父消息下已无活跃子消息时才回滚：有活跃子说明新助手回复已经落库，
     * 此时再点亮旧分支会让同一层出现两条活跃助手消息，历史出现分叉，是更糟的坏状态
     * （覆盖「工具链跑到一半才失败」这种已有产出的情况）。
     * <p>
     * 本方法自行吞掉异常：它跑在失败路径上，不能反过来盖掉原始错误。
     *
     * @param request   本轮请求（仅 replace 模式生效）
     * @param sessionId 会话 ID
     */
    public void rollbackReplacedBranch(ChatMessageRequest request, String sessionId) {
        if (request == null || !ChatMessageRequest.MODE_REPLACE.equals(request.getMode())) {
            return;
        }
        String replaceMessageId = request.getReplaceMessageId();
        if (!StringUtils.hasText(replaceMessageId) || !StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            ChatMessage replaced = chatMessageService.findById(replaceMessageId);
            if (replaced == null || Boolean.TRUE.equals(replaced.getActive())) {
                // 消息不存在，或它还是活跃的（本轮压根没走到停用那一步）—— 无需回滚
                return;
            }
            String parentId = replaced.getParentId();
            boolean hasActiveSibling = chatMessageService.findBySessionId(sessionId).stream()
                    .anyMatch(m -> parentId != null && parentId.equals(m.getParentId())
                            && Boolean.TRUE.equals(m.getActive()));
            if (hasActiveSibling) {
                return;
            }
            int affected = chatMessageService.reactivateBranch(replaceMessageId);
            log.warn("replace 失败，旧助手分支已回滚: sessionId={}, replaceMessageId={}, 恢复 {} 行",
                    sessionId, replaceMessageId, affected);
        } catch (Exception e) {
            log.error("回滚 replace 旧助手分支失败: sessionId={}, replaceMessageId={}, {}",
                    sessionId, replaceMessageId, e.getMessage(), e);
        }
    }
}
