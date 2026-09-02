package com.fishsunny.assistant.engine.adapter.text;

/*
 * @Usage Text 协议流式适配器，content 为 String（非数组），比 StandardStreamAIAdapter 更简单
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
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.text.TextAIRequest;
import com.fishsunny.assistant.engine.protocol.text.TextStreamAIResponse;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextAssistantMessage;
import com.fishsunny.assistant.engine.protocol.text.response.TextStreamChoice;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TextStreamAIAdapter extends TextBaseAIAdapter {

    public TextStreamAIAdapter(AIAdapterOption option) throws Exception {
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
                .setStream(true)
                .setThinking(new StandardAIThinking(thinking))
                .setReasoning_effort(settings.getReasoning_effort())
                .setFrequency_penalty(settings.getFrequency_penalty())
                .setMax_tokens(settings.getMax_tokens())
                .setPresence_penalty(settings.getPresence_penalty())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setMessages(convertToTextMessage(chatRequest.getMessages()))
                .setTools(chatRequest.getTools());

        // 映射 response_format
        if (settings.getResponse_format() != null) {
            textAIRequest.setResponse_format(
                    new TextAIRequest.ResponseFormat(settings.getResponse_format().getType()));
        }

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
        TextStreamAIResponse textAIResponse = (TextStreamAIResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        TextStreamChoice[] choices = textAIResponse.getChoices();
        List<TextMessage> textMessages = new ArrayList<>();
        for (TextStreamChoice choice : choices) {
            textMessages.add(choice.getDelta());
            if (StringUtils.hasText(choice.getFinish_reason())) {
                chatResponse.setStatus(ChatResponse.STATUS_DONE);
            } else {
                chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
            }
        }

        chatResponse.setMessages(convertToChatMessage(textMessages));

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        if (!targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        TextStreamAIResponse textAIResponse = (TextStreamAIResponse) response;
        return textAIResponse.getChoices().length > 0
                && textAIResponse.getChoices()[0].getFinish_reason() != null;
    }

    private final Map<String, StandardToolRequest> toolCallMap = new HashMap<>();
    private String currentToolCallId;
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder content = new StringBuilder();

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        TextStreamAIResponse textAIResponse = (TextStreamAIResponse) response;
        for (TextStreamChoice choice : textAIResponse.getChoices()) {
            if (choice.getDelta() instanceof TextAssistantMessage assistantMessage) {
                if (assistantMessage.getReasoning_content() != null) {
                    reasoning.append(assistantMessage.getReasoning_content());
                }
                if (assistantMessage.getContent() != null) {
                    content.append(assistantMessage.getContent());
                }
                if (assistantMessage.getTool_calls() != null) {
                    for (StandardToolRequest toolCall : assistantMessage.getTool_calls()) {
                        if (toolCall.getId() != null) {
                            // 归一化：首个 chunk 的 arguments 若为 null 则设为空串
                            if (toolCall.getFunction().getArguments() == null) {
                                toolCall.getFunction().setArguments("");
                            }
                            toolCallMap.put(toolCall.getId(), toolCall);
                            currentToolCallId = toolCall.getId();
                        } else {
                            if (toolCall.getFunction().getArguments() == null) {
                                continue;
                            }
                            StandardToolRequest storageToolCall = toolCallMap.get(currentToolCallId);
                            String arguments = storageToolCall.getFunction().getArguments()
                                    + toolCall.getFunction().getArguments();
                            storageToolCall.getFunction().setArguments(arguments);
                        }
                    }
                }
                return !CollectionUtils.isEmpty(assistantMessage.getTool_calls());
            }
        }
        return false;
    }

    @Override
    public List<ToolCall> getToolCalls() {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (StandardToolRequest toolCall : toolCallMap.values()) {
            ToolCall.Function function = new ToolCall.Function()
                    .setName(toolCall.getFunction().getName())
                    .setArguments(toolCall.getFunction().getArguments());
            ToolCall adapterToolCall = new ToolCall()
                    .setIndex(toolCall.getIndex())
                    .setId(toolCall.getId())
                    .setFunction(function);
            toolCalls.add(adapterToolCall);
        }
        return toolCalls;
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
    }
}
