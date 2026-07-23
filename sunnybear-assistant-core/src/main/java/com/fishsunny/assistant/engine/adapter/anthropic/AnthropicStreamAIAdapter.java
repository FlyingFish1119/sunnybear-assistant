package com.fishsunny.assistant.engine.adapter.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicAIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicThinking;
import com.fishsunny.assistant.engine.protocol.anthropic.stream.*;
import com.fishsunny.assistant.engine.protocol.anthropic.tools.AnthropicToolRegister;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.*;
import com.fishsunny.assistant.variable.RoleVariable;
import org.springframework.util.CollectionUtils;

import java.util.*;

public class AnthropicStreamAIAdapter extends AnthropicBaseAIAdapter {

    public AnthropicStreamAIAdapter(AIAdapterOption option) throws Exception {
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
                .setStream(true)
                .setMax_tokens(settings.getMax_tokens() != null ? settings.getMax_tokens() : 4096)
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setSystem(extractSystemPrompt(chatRequest.getMessages()))
                .setMessages(convertToAnthropicMessages(chatRequest.getMessages()));

        if (Boolean.TRUE.equals(settings.getThinking())) {
            int budgetTokens = settings.getMax_tokens() != null
                    ? Math.max(1024, settings.getMax_tokens() / 2)
                    : 16000;
            anthropicRequest.setThinking(AnthropicThinking.enabled(budgetTokens));
        }

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
        if (!super.targetRespCls.isAssignableFrom(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        AnthropicStreamResponse event = (AnthropicStreamResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        switch (event.getType()) {
            case AnthropicStreamResponse.TYPE_PING:
                chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                break;

            case AnthropicStreamResponse.TYPE_CONTENT_BLOCK_DELTA: {
                Integer idx = event.getIndex();
                JsonNode deltaNode = event.getDelta();
                if (deltaNode != null) {
                    try {
                        AnthropicDelta delta = objectMapper.treeToValue(deltaNode, AnthropicDelta.class);
                        if (delta instanceof AnthropicTextDelta textDelta && textDelta.getText() != null) {
                            chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                            ChatMessage msg = new ChatMessage()
                                    .setRole(RoleVariable.ROLE_ASSISTANT)
                                    .text(textDelta.getText());
                            chatResponse.setMessages(List.of(msg));
                        } else if (delta instanceof AnthropicInputJsonDelta) {
                            // Tool call streaming: include partial tool call info (like OpenAI adapter)
                            ToolUseMeta meta = idx != null ? contentBlockToolUse.get(idx) : null;
                            if (meta != null) {
                                chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                                ChatMessage msg = new ChatMessage()
                                        .setRole(RoleVariable.ROLE_ASSISTANT);
                                com.fishsunny.assistant.engine.protocol.project.ChatToolRequest toolReq =
                                        new com.fishsunny.assistant.engine.protocol.project.ChatToolRequest();
                                toolReq.setId(meta.id);
                                toolReq.setName(meta.name);
                                toolReq.setArguments(meta.inputJson.toString());
                                msg.setToolCalls(List.of(toolReq));
                                chatResponse.setMessages(List.of(msg));
                            } else {
                                chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                            }
                        } else {
                            chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                        }
                    } catch (Exception e) {
                        log.warn("Anthropic delta 反序列化失败 (idx={}): {}", idx, e.getMessage());
                        chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                    }
                } else {
                    chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                }
                break;
            }
            case AnthropicStreamResponse.TYPE_MESSAGE_STOP:
                chatResponse.setStatus(ChatResponse.STATUS_DONE);
                break;
            default:
                chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                break;
        }

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        AnthropicStreamResponse event = (AnthropicStreamResponse) response;
        return AnthropicStreamResponse.TYPE_MESSAGE_STOP.equals(event.getType());
    }

    // ==================== Accumulation state ====================

    private final Map<Integer, ToolUseMeta> contentBlockToolUse = new LinkedHashMap<>();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningSignature;

    private static class ToolUseMeta {
        String id;
        String name;
        final StringBuilder inputJson = new StringBuilder();

        ToolUseMeta(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        AnthropicStreamResponse event = (AnthropicStreamResponse) response;

        switch (event.getType()) {
            case AnthropicStreamResponse.TYPE_MESSAGE_START:
                contentBlockToolUse.clear();
                content.setLength(0);
                reasoning.setLength(0);
                reasoningSignature = null;
                return false;

            case AnthropicStreamResponse.TYPE_PING:
                return false;

            case AnthropicStreamResponse.TYPE_ERROR: {
                JsonNode errorNode = event.getError();
                if (errorNode != null) {
                    log.error("Anthropic SSE error: {}", errorNode);
                } else {
                    log.error("Anthropic SSE error (no detail): {}", event.getType());
                }
                return false;
            }

            case AnthropicStreamResponse.TYPE_CONTENT_BLOCK_START: {
                Integer idx = event.getIndex();
                AnthropicStreamContentBlock cb = event.getContent_block();
                if (cb != null && idx != null && "tool_use".equals(cb.getType())) {
                    contentBlockToolUse.put(idx, new ToolUseMeta(cb.getId(), cb.getName()));
                }
                return false;
            }

            case AnthropicStreamResponse.TYPE_CONTENT_BLOCK_DELTA: {
                Integer idx = event.getIndex();
                if (idx == null) return false;

                JsonNode deltaNode = event.getDelta();
                if (deltaNode == null) return false;

                try {
                    AnthropicDelta delta = objectMapper.treeToValue(deltaNode, AnthropicDelta.class);
                    if (delta instanceof AnthropicTextDelta textDelta && textDelta.getText() != null) {
                        content.append(textDelta.getText());
                    } else if (delta instanceof AnthropicThinkingDelta thinkingDelta && thinkingDelta.getThinking() != null) {
                        reasoning.append(thinkingDelta.getThinking());
                    } else if (delta instanceof AnthropicInputJsonDelta inputJsonDelta && inputJsonDelta.getPartial_json() != null) {
                        ToolUseMeta meta = contentBlockToolUse.get(idx);
                        if (meta != null) {
                            meta.inputJson.append(inputJsonDelta.getPartial_json());
                        }
                    } else if (delta instanceof AnthropicSignatureDelta signatureDelta && signatureDelta.getSignature() != null) {
                        // Anthropic 在同一响应中只有一个 thinking block，取最后一个签名
                        reasoningSignature = signatureDelta.getSignature();
                    }
                } catch (Exception e) {
                    log.warn("Anthropic content_block_delta 反序列化失败 (idx={}): {}", idx, e.getMessage());
                }
                return false;
            }

            case AnthropicStreamResponse.TYPE_CONTENT_BLOCK_STOP:
                return false;

            case AnthropicStreamResponse.TYPE_MESSAGE_DELTA: {
                JsonNode deltaNode = event.getDelta();
                return deltaNode != null && "tool_use".equals(
                        deltaNode.has("stop_reason") ? deltaNode.get("stop_reason").asText() : null);
            }

            case AnthropicStreamResponse.TYPE_MESSAGE_STOP:
                return !contentBlockToolUse.isEmpty();

            default:
                log.debug("Anthropic SSE unhandled event type: {}", event.getType());
                return false;
        }
    }

    @Override
    public List<ToolCall> getToolCalls() {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolUseMeta meta : contentBlockToolUse.values()) {
            ToolCall.Function function = new ToolCall.Function()
                    .setName(meta.name)
                    .setArguments(meta.inputJson.toString());
            toolCalls.add(new ToolCall().setId(meta.id).setFunction(function));
        }
        return toolCalls;
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
        if (!AnthropicAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!AnthropicStreamResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }

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
