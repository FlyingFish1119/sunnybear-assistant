package com.fishsunny.assistant.engine.adapter.responses;

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIRequest;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Responses API 非流式适配器：一次请求返回完整响应体。
 *
 * <p>{@code finished()} 恒为 true——收流循环读到这唯一一帧就结束（与 {@code GeminiAIAdapter} 同形）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public class ResponsesAIAdapter extends ResponsesBaseAIAdapter {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningSignature;
    private List<ToolCall> toolCallCache = new ArrayList<>();

    public ResponsesAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    protected boolean streaming() {
        return false;
    }

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!super.targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        ResponsesAIResponse responsesResponse = (ResponsesAIResponse) response;

        if (ResponsesAIResponse.STATUS_FAILED.equals(responsesResponse.getStatus())
                || responsesResponse.getError() != null) {
            throw readableFailure(responsesResponse);
        }
        if (ResponsesAIResponse.STATUS_INCOMPLETE.equals(responsesResponse.getStatus())) {
            // 被输出上限截断：已有内容仍有价值，告警后照常下发
            log.warn("Responses 非流式响应未完成（status=incomplete），按已有内容继续: {}",
                    responsesResponse.getIncomplete_details());
        }

        return new ChatResponse()
                .setStatus(ChatResponse.STATUS_DONE)
                .setMessages(convertOutputToMessages(responsesResponse));
    }

    @Override
    public boolean finished(AIResponse response) {
        return true;
    }

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!super.targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        // 非流式只有一帧，先清空再解析，避免适配器实例复用（工厂每次新建，这里只为行为确定）
        content.setLength(0);
        reasoning.setLength(0);
        reasoningSignature = null;
        toolCallCache = new ArrayList<>();

        ParsedOutput parsed = parseOutput(((ResponsesAIResponse) response).getOutput());
        content.append(parsed.text());
        reasoning.append(parsed.reasoning());
        reasoningSignature = parsed.reasoningSignature();
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
        if (!ResponsesAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!ResponsesAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
