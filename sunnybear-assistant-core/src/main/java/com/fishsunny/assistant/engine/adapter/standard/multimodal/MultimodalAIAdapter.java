package com.fishsunny.assistant.engine.adapter.standard.multimodal;

/*
 * @Usage 多模态 tool 结果协议 —— 非流式适配器。
 *        独立协议族，不复用 Standard 适配器；响应侧复用 Standard 响应数据类（只读）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/2
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.MultimodalAIRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.StandardAIResponse;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.response.StandardChoice;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class MultimodalAIAdapter extends MultimodalBaseAIAdapter {

    public MultimodalAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        MultimodalAIRequest multimodalRequest = new MultimodalAIRequest();

        ChatSettings settings = chatRequest.getSettings();

        String thinking = Boolean.TRUE.equals(settings.getThinking()) ? "enabled" : "disabled";
        multimodalRequest.setModel(settings.getModel())
                .setStream(false)  // 非流式
                .setThinking(new StandardAIThinking(thinking))
                .setReasoning_effort(settings.getReasoning_effort())
                .setFrequency_penalty(settings.getFrequency_penalty())
                .setMax_tokens(settings.getMax_tokens())
                .setPresence_penalty(settings.getPresence_penalty())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setMessages(convertToMultimodalMessage(chatRequest.getMessages()))
                .setTools(chatRequest.getTools());

        if (settings.getResponse_format() != null) {
            multimodalRequest.setResponse_format(
                    new MultimodalAIRequest.ResponseFormat(settings.getResponse_format().getType()));
        }

        return multimodalRequest;
    }

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!super.targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        StandardAIResponse standardAIResponse = (StandardAIResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        StandardChoice[] choices = standardAIResponse.getChoices();
        List<StandardMessage> standardMessages = new ArrayList<>();
        for (StandardChoice choice : choices) {
            standardMessages.add(choice.getMessage());
        }

        chatResponse.setMessages(convertToChatMessage(standardMessages))
                .setStatus(ChatResponse.STATUS_DONE);

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        return true;
    }

    private final List<ToolCall> toolCallCache = new ArrayList<>();
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder content = new StringBuilder();

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        StandardAIResponse standardAIResponse = (StandardAIResponse) response;
        toolCallCache.clear();
        for (StandardChoice choice : standardAIResponse.getChoices()) {
            if (choice.getMessage() instanceof StandardAssistantMessage assistantMessage) {
                if (assistantMessage.getReasoning_content() != null) {
                    reasoning.append(assistantMessage.getReasoning_content());
                }
                if (assistantMessage.getContent() != null) {
                    content.append(assistantMessage.getContent());
                }
                List<StandardToolRequest> toolCalls = assistantMessage.getTool_calls();
                if (!CollectionUtils.isEmpty(toolCalls)) {
                    for (StandardToolRequest toolCall : toolCalls) {
                        ToolCall.Function function = new ToolCall.Function()
                                .setName(toolCall.getFunction().getName())
                                .setArguments(toolCall.getFunction().getArguments() == null ? "" : toolCall.getFunction().getArguments());
                        ToolCall adapterToolCall = new ToolCall()
                                .setIndex(toolCall.getIndex())
                                .setId(toolCall.getId())
                                .setFunction(function);
                        toolCallCache.add(adapterToolCall);
                    }
                    return true;
                }
            }
        }
        return false;
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
        if (!MultimodalAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!StandardAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
