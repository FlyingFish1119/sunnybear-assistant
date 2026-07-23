package com.fishsunny.assistant.engine.adapter.standard;

/*
 * @Usage 非流式标准 AI 适配器，与 StandardStreamAIAdapter 对应，
 *        使用 StandardAIResponse（完整 message）替代 StandardStreamAIResponse（增量 delta）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 08:00
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.chat.StandardAIRequest;
import com.fishsunny.assistant.engine.protocol.standard.chat.StandardAIResponse;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.chat.response.StandardChoice;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequest;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class StandardAIAdapter extends StandardBaseAIAdapter {

    public StandardAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        StandardAIRequest standardAIRequest = new StandardAIRequest();

        ChatSettings settings = chatRequest.getSettings();

        String thinking = Boolean.TRUE.equals(settings.getThinking()) ? "enabled" : "disabled";
        standardAIRequest.setModel(settings.getModel())
                .setStream(false)  // 非流式：设置为 false
                .setThinking(new StandardAIThinking(thinking))
                .setReasoning_effort(settings.getReasoning_effort())
                .setFrequency_penalty(settings.getFrequency_penalty())
                .setMax_tokens(settings.getMax_tokens())
                .setPresence_penalty(settings.getPresence_penalty())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setMessages(convertToStandardMessage(chatRequest.getMessages()))
                .setTools(chatRequest.getTools());

        return standardAIRequest;
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
            // 非流式：使用 getMessage() 获取完整消息，而非流式的 getDelta()
            standardMessages.add(choice.getMessage());
        }

        chatResponse.setMessages(convertToChatMessage(standardMessages))
                .setStatus(ChatResponse.STATUS_DONE);

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        // 非流式响应本身就是完整的，直接返回 true
        return true;
    }

    /**
     * 非流式工具调用缓存：一次响应中所有工具调用一次性完整返回，无需像流式那样增量拼接。
     * 在 toolsUsed() 中填充，在 getFunctionCall() 中返回。
     */
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
        if (!StandardAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        // 非流式：targetRespCls 应该是 StandardAIResponse（而非 StandardStreamAIResponse）
        if (!StandardAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
