package com.fishsunny.assistant.engine.adapter.anthropic;

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicAIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicAIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicThinking;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicContentBlock;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicTextContent;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicThinkingContent;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicToolUseContent;
import com.fishsunny.assistant.engine.protocol.anthropic.tools.AnthropicToolRegister;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.*;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnthropicAIAdapter extends AnthropicBaseAIAdapter {

    public AnthropicAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        AnthropicAIRequest anthropicRequest = new AnthropicAIRequest();

        ChatSettings settings = chatRequest.getSettings();

        anthropicRequest.setModel(settings.getModel())
                .setStream(false)
                .setMax_tokens(settings.getMax_tokens() != null ? settings.getMax_tokens() : 4096)
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setSystem(extractSystemPrompt(chatRequest.getMessages()))
                .setMessages(convertToAnthropicMessages(chatRequest.getMessages()));

        // Thinking config: only set when enabled (don't send disabled)
        if (Boolean.TRUE.equals(settings.getThinking())) {
            int budgetTokens = settings.getMax_tokens() != null
                    ? Math.max(1024, settings.getMax_tokens() / 2)
                    : 16000;
            anthropicRequest.setThinking(AnthropicThinking.enabled(budgetTokens));
        }

        // Convert tools from StandardToolRegister (OpenAI format) to Anthropic format
        if (!CollectionUtils.isEmpty(chatRequest.getTools())) {
            List<AnthropicToolRegister> anthropicTools = new ArrayList<>();
            for (StandardToolRegister tool : chatRequest.getTools()) {
                AnthropicToolRegister aTool = new AnthropicToolRegister()
                        .setName(tool.getFunction().getName())
                        .setDescription(tool.getFunction().getDescription());
                if (tool.getFunction().getParameters() != null) {
                    aTool.setInput_schema(convertParametersToSchema(tool.getFunction().getParameters()));
                }
                anthropicTools.add(aTool);
            }
            anthropicRequest.setTools(anthropicTools);
        }

        return anthropicRequest;
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
        AnthropicAIResponse anthropicResponse = (AnthropicAIResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        chatResponse.setMessages(convertContentBlocksToMessages(anthropicResponse.getContent()))
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
        AnthropicAIResponse anthropicResponse = (AnthropicAIResponse) response;
        toolCallCache.clear();
        reasoning.setLength(0);
        content.setLength(0);

        for (AnthropicContentBlock block : anthropicResponse.getContent()) {
            if (block instanceof AnthropicTextContent textBlock) {
                if (textBlock.getText() != null) {
                    content.append(textBlock.getText());
                }
            } else if (block instanceof AnthropicThinkingContent thinkingBlock) {
                if (thinkingBlock.getThinking() != null) {
                    reasoning.append(thinkingBlock.getThinking());
                }
            } else if (block instanceof AnthropicToolUseContent toolUse) {
                ToolCall.Function function = new ToolCall.Function()
                        .setName(toolUse.getName());
                try {
                    function.setArguments(toolUse.getInput() != null
                            ? objectMapper.writeValueAsString(toolUse.getInput()) : "{}");
                } catch (Exception e) {
                    function.setArguments("{}");
                }
                ToolCall adapterToolCall = new ToolCall()
                        .setId(toolUse.getId())
                        .setFunction(function);
                toolCallCache.add(adapterToolCall);
            }
        }
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
        if (!AnthropicAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!AnthropicAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }

    /**
     * Convert StandardToolRegisterParameter (OpenAI format) to a Map suitable for
     * Anthropic's input_schema field.
     */
    private Map<String, Object> convertParametersToSchema(StandardToolRegisterParameter params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (!CollectionUtils.isEmpty(params.getProperties())) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (Map.Entry<String, StandardToolRegisterProperty> entry : params.getProperties().entrySet()) {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", entry.getValue().getType());
                prop.put("description", entry.getValue().getDescription());
                properties.put(entry.getKey(), prop);
            }
            schema.put("properties", properties);
        }

        if (!CollectionUtils.isEmpty(params.getRequired())) {
            schema.put("required", params.getRequired());
        }

        return schema;
    }
}
