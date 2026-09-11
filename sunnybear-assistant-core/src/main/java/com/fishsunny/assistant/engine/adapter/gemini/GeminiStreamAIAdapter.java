package com.fishsunny.assistant.engine.adapter.gemini;

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIRequest;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiPromptFeedback;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiCandidate;
import com.fishsunny.assistant.engine.protocol.gemini.stream.GeminiStreamResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini 流式适配器（:streamGenerateContent?alt=sse）。
 *
 * <p>Gemini 的 SSE 没有事件类型信封，每帧就是一个完整的 GenerateContentResponse：
 * 正文按增量给出，finishReason 只在最后一帧出现，适配器据此判定收流结束。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public class GeminiStreamAIAdapter extends GeminiBaseAIAdapter {

    public GeminiStreamAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    protected String endpointSuffix() {
        return ":streamGenerateContent?alt=sse";
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        ChatSettings settings = chatRequest.getSettings();

        GeminiAIRequest geminiRequest = new GeminiAIRequest()
                .setModel(settings.getModel())
                .setContents(convertToGeminiContents(chatRequest.getMessages()))
                .setSystemInstruction(extractSystemInstruction(chatRequest.getMessages()))
                .setTools(convertTools(chatRequest.getTools()))
                .setGenerationConfig(buildGenerationConfig(settings))
                .setSafetySettings(buildSafetySettings(settings.getCustomFields()));
        geminiRequest.setExtraBody(settings.getCustomFields());
        return geminiRequest;
    }

    /**
     * 本帧的增量转 master：正文、思考摘要、工具调用都随帧下发。
     * <p>思考摘要必须进增量——前端只在 chunk/done 上累加 reasoningContent，
     * 最终落库的 assistant 消息（init_assistant）只同步元数据、不覆盖正文，
     * 少了下发这一路，思考过程要等刷新页面才看得见。
     */
    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        GeminiCandidate candidate = ((GeminiStreamResponse) response).firstCandidate();
        ChatResponse chatResponse = new ChatResponse();

        if (candidate == null) {
            // 被安全策略拦截时流里没有候选，抛出去让调用方看到原因，而不是留一条空回复
            GeminiPromptFeedback feedback = ((GeminiStreamResponse) response).getPromptFeedback();
            if (feedback != null && StringUtils.hasText(feedback.getBlockReason())) {
                throw noCandidateError((GeminiStreamResponse) response);
            }
            chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
            return chatResponse;
        }

        ParsedParts parsed = parseParts(candidate.getContent() == null ? null : candidate.getContent().getParts());
        ChatMessage message = new ChatMessage().setRole(ChatMessage.ROLE_ASSISTANT);

        if (!parsed.text().isEmpty()) {
            message.text(parsed.text());
        }
        if (!parsed.reasoning().isEmpty()) {
            message.setReasoningContent(parsed.reasoning());
        }
        if (!parsed.toolCalls().isEmpty()) {
            message.setToolCalls(parsed.toolCalls().stream()
                    .map(toolCall -> new ChatToolRequest()
                            .setId(toolCall.getId())
                            .setName(toolCall.getFunction().getName())
                            .setArguments(toolCall.getFunction().getArguments()))
                    .toList());
        }

        chatResponse.setStatus(candidate.isFinished() ? ChatResponse.STATUS_DONE : ChatResponse.STATUS_CHUNK);
        if (!message.getContents().isEmpty() || !message.getToolCalls().isEmpty()
                || StringUtils.hasText(message.getReasoningContent())) {
            chatResponse.setMessages(List.of(message));
        }
        return chatResponse;
    }

    /**
     * 收流结束判定：finishReason 非空即本轮结束。
     */
    @Override
    public boolean finished(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        GeminiCandidate candidate = ((GeminiStreamResponse) response).firstCandidate();
        if (candidate == null) {
            return false;
        }
        if (candidate.isFinished()) {
            warnOnAbnormalFinish(candidate);
            return true;
        }
        return false;
    }

    // ==================== 累积状态（供 TranslateResult 取整轮结果） ====================

    private final List<ToolCall> toolCallCache = new ArrayList<>();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningSignature;

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        GeminiCandidate candidate = ((GeminiStreamResponse) response).firstCandidate();
        if (candidate == null) {
            return !toolCallCache.isEmpty();
        }
        ParsedParts parsed = parseParts(candidate.getContent() == null ? null : candidate.getContent().getParts());

        content.append(parsed.text());
        reasoning.append(parsed.reasoning());
        if (parsed.thoughtSignature() != null) {
            // 签名可能挂在多个 part 上，取最后一个（与 Anthropic 适配器同一口径）
            reasoningSignature = parsed.thoughtSignature();
        }
        toolCallCache.addAll(parsed.toolCalls());
        return !parsed.toolCalls().isEmpty();
    }

    @Override
    public List<ToolCall> getToolCalls() {
        return new ArrayList<>(toolCallCache);
    }

    @Override
    public String getReasoning() {
        return reasoning.toString();
    }

    @Override
    public String getReasoningSignature() {
        return reasoningSignature;
    }

    @Override
    public String getContent() {
        return content.toString();
    }

    @Override
    public void checkCls(Class<? extends AIRequest> masterCls,
                         Class<? extends AIRequest> targetCls,
                         Class<? extends AIResponse> masterRespCls,
                         Class<? extends AIResponse> targetRespCls) throws Exception {
        if (!ChatRequest.class.equals(masterCls)) {
            throw new RuntimeException("Invalid master class: " + masterCls.getName());
        }
        if (!GeminiAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!GeminiStreamResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
