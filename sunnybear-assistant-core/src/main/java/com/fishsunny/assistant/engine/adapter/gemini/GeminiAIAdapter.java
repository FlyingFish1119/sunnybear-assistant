package com.fishsunny.assistant.engine.adapter.gemini;

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIRequest;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiCandidate;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini 非流式适配器（:generateContent）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public class GeminiAIAdapter extends GeminiBaseAIAdapter {

    public GeminiAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    protected String endpointSuffix() {
        return ":generateContent";
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

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!super.targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setMessages(convertResponseToMessages((GeminiAIResponse) response))
                .setStatus(ChatResponse.STATUS_DONE);
        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        return true;
    }

    // ==================== 累积状态（供 TranslateResult 取整轮结果） ====================

    private final List<ToolCall> toolCallCache = new ArrayList<>();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningSignature;

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        toolCallCache.clear();
        content.setLength(0);
        reasoning.setLength(0);
        reasoningSignature = null;

        GeminiCandidate candidate = ((GeminiAIResponse) response).firstCandidate();
        if (candidate == null) {
            return false;
        }
        ParsedParts parsed = parseParts(candidate.getContent() == null ? null : candidate.getContent().getParts());
        content.append(parsed.text());
        reasoning.append(parsed.reasoning());
        reasoningSignature = parsed.thoughtSignature();
        toolCallCache.addAll(parsed.toolCalls());
        return !toolCallCache.isEmpty();
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
        if (!GeminiAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
