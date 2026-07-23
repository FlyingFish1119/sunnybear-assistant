package com.fishsunny.assistant.engine.adapter.text;

/*
 * @Usage Text 协议非流式适配器，content 为 String（非数组），比 StandardAIAdapter 更简单
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:30
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.chat.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.text.TextAIRequest;
import com.fishsunny.assistant.engine.protocol.text.TextAIResponse;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextAssistantMessage;
import com.fishsunny.assistant.engine.protocol.text.response.TextChoice;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class TextAIAdapter extends TextBaseAIAdapter {

    public TextAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        TextAIRequest textAIRequest = new TextAIRequest();

        ChatSettings settings = chatRequest.getSettings();

        String thinking = Boolean.TRUE.equals(settings.getThinking()) ? "enabled" : "disabled";
        textAIRequest.setModel(settings.getModel())
                .setStream(false)
                .setThinking(new StandardAIThinking(thinking))
                .setReasoning_effort(settings.getReasoning_effort())
                .setFrequency_penalty(settings.getFrequency_penalty())
                .setMax_tokens(settings.getMax_tokens())
                .setPresence_penalty(settings.getPresence_penalty())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setMessages(convertToTextMessage(chatRequest.getMessages()))
                .setTools(chatRequest.getTools());

        return textAIRequest;
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
        TextAIResponse textAIResponse = (TextAIResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        TextChoice[] choices = textAIResponse.getChoices();
        List<TextMessage> textMessages = new ArrayList<>();
        for (TextChoice choice : choices) {
            textMessages.add(choice.getMessage());
        }

        chatResponse.setMessages(convertToChatMessage(textMessages))
                .setStatus(ChatResponse.STATUS_DONE);

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        // 非流式响应本身就是完整的
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
        TextAIResponse textAIResponse = (TextAIResponse) response;
        toolCallCache.clear();
        for (TextChoice choice : textAIResponse.getChoices()) {
            if (choice.getMessage() instanceof TextAssistantMessage assistantMessage) {
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
        if (!TextAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!TextAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
