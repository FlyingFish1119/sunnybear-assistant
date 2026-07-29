package com.fishsunny.assistant.engine.adapter.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.*;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicAssistantMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicUserMessage;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import com.fishsunny.assistant.variable.RoleVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AnthropicBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AnthropicBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    /** Anthropic API version header value */
    protected static final String ANTHROPIC_VERSION = "2023-06-01";

    public AnthropicBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        return null;
    }

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        return null;
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        return null;
    }

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        return null;
    }

    @Override
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(super.baseUrl))
                .header("Content-Type", "application/json")
                .header("x-api-key", super.apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<Stream<String>> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            try (Stream<String> bodyStream = response.body()) {
                String errorMessage = bodyStream.collect(Collectors.joining("\n"));
                log.info("Anthropic API error: {}", errorMessage);
                throw new RuntimeException("Invalid status code: " + response.statusCode() + ", error: " + errorMessage);
            }
        } else {
            return response.body();
        }
    }

    @Override
    public boolean finished(AIResponse response) {
        return false;
    }

    @Override
    public boolean collectChunk(AIResponse response) {
        return false;
    }

    @Override
    public List<ToolCall> getToolCalls() {
        return List.of();
    }

    @Override
    public void checkCls(Class<? extends AIRequest> masterCls,
                         Class<? extends AIRequest> targetCls,
                         Class<? extends AIResponse> masterRespCls,
                         Class<? extends AIResponse> targetRespCls) throws Exception {
    }

    // ==================== Message Conversion ====================

    /**
     * Convert internal ChatMessage list to Anthropic message format.
     * System messages are extracted to a separate string (returned as the first element
     * in a synthetic result; callers should use {@link #extractSystemPrompt} instead).
     *
     * <p>Anthropic does not have a "system" role in messages — system prompts go to
     * the top-level "system" field. Tool results (role=tool) become user messages
     * with tool_result content blocks.
     */
    protected List<AnthropicMessage> convertToAnthropicMessages(List<ChatMessage> messages) {
        List<AnthropicMessage> anthropicMessages = new ArrayList<>();
        List<AnthropicToolResultContent> pendingToolResults = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (RoleVariable.ROLE_SYSTEM.equals(message.getRole())) {
                // System messages are handled separately via extractSystemPrompt()
                continue;
            }
            switch (message.getRole()) {
                case RoleVariable.ROLE_USER: {
                    // Flush pending tool results before adding user message
                    flushToolResults(anthropicMessages, pendingToolResults);
                    AnthropicUserMessage userMessage = new AnthropicUserMessage();
                    userMessage.setContent(convertToAnthropicContentBlocks(message.getContents()));
                    anthropicMessages.add(userMessage);
                    break;
                }
                case RoleVariable.ROLE_ASSISTANT: {
                    // Flush pending tool results before adding assistant message
                    flushToolResults(anthropicMessages, pendingToolResults);
                    AnthropicAssistantMessage assistantMessage = new AnthropicAssistantMessage();
                    List<AnthropicContentBlock> blocks = new ArrayList<>();
                    // reasoning / thinking text
                    if (message.getReasoningContent() != null && !message.getReasoningContent().isEmpty()) {
                        String sig = message.getReasoningSignature();
                        blocks.add(new AnthropicThinkingContent()
                                .setThinking(message.getReasoningContent())
                                .setSignature(sig != null ? sig : ""));
                    }
                    // text content
                    String text = message.resolveText();
                    if (text != null && !text.isEmpty()) {
                        blocks.add(new AnthropicTextContent(text));
                    }
                    // tool_use blocks
                    for (ChatToolRequest toolRequest : message.getToolCalls()) {
                        try {
                            AnthropicToolUseContent toolUse = new AnthropicToolUseContent()
                                    .setId(toolRequest.getId())
                                    .setName(toolRequest.getName());
                            if (toolRequest.getArguments() != null && !toolRequest.getArguments().isEmpty()) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> input = objectMapper.readValue(
                                        toolRequest.getArguments(), java.util.Map.class);
                                toolUse.setInput(input);
                            }
                            blocks.add(toolUse);
                        } catch (JsonProcessingException e) {
                            log.error("Failed to parse tool arguments: {}", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    assistantMessage.setContent(blocks);
                    anthropicMessages.add(assistantMessage);
                    break;
                }
                case RoleVariable.ROLE_TOOL:
                    // Collect tool results; flush as one user message when the next
                    // non-tool message arrives (or at end). Anthropic requires all
                    // tool_result blocks for an assistant's tool_use blocks to be in
                    // a single user message immediately following the assistant.
                    pendingToolResults.add(
                            new AnthropicToolResultContent(message.getToolCallId(), message.resolveText()));
                    break;
                default:
                    throw new RuntimeException("Invalid role for Anthropic: " + message.getRole());
            }
        }
        // Flush any remaining tool results at end
        flushToolResults(anthropicMessages, pendingToolResults);
        return anthropicMessages;
    }

    private void flushToolResults(List<AnthropicMessage> messages, List<AnthropicToolResultContent> pending) {
        if (pending.isEmpty()) return;
        AnthropicUserMessage toolResultMessage = new AnthropicUserMessage();
        toolResultMessage.setContent(new ArrayList<>(pending));
        messages.add(toolResultMessage);
        pending.clear();
    }

    /**
     * Extract system prompt from ChatMessage list.
     * Returns the concatenated text of all system messages, or null if none.
     */
    protected String extractSystemPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (RoleVariable.ROLE_SYSTEM.equals(message.getRole())) {
                String text = message.resolveText();
                if (text != null && !text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ==================== Content Block Conversion ====================

    /**
     * Convert internal MessageContent list to Anthropic content blocks.
     */
    protected List<AnthropicContentBlock> convertToAnthropicContentBlocks(List<MessageContent> contents) {
        List<AnthropicContentBlock> blocks = new ArrayList<>();
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                blocks.add(new AnthropicTextContent(textContent.getContent()));
            } else if (content instanceof ImageContent imageContent) {
                // Convert image URL (data URI or regular URL) to Anthropic base64 source
                String url = imageContent.getUrl();
                if (url != null && url.startsWith("data:")) {
                    String base64Data = Base64Utils.extractBase64FromDataUri(url);
                    String extension = Base64Utils.getExtensionFromDataUri(url);
                    String mediaType = Base64Utils.getMimeTypeByExtension(extension);
                    if (base64Data != null) {
                        blocks.add(new AnthropicImageContent(
                                new AnthropicImageSource(mediaType, base64Data)));
                    } else {
                        log.warn("Unable to parse image data URI, skipping: {}", url);
                    }
                } else if (url != null) {
                    // External URL — Anthropic supports image_url type directly
                    // For now, log a warning as this requires fetching the image
                    log.warn("Anthropic adapter: external image URLs are not yet supported, skipping: {}", url);
                }
            }
        }
        return blocks;
    }

    // ==================== Response → ChatMessage Conversion ====================

    /**
     * Convert Anthropic content blocks back to internal ChatMessage list.
     */
    protected List<ChatMessage> convertContentBlocksToMessages(List<AnthropicContentBlock> blocks) {
        List<ChatMessage> messages = new ArrayList<>();

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setRole(RoleVariable.ROLE_ASSISTANT);

        StringBuilder textBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<ChatToolRequest> toolCalls = new ArrayList<>();

        for (AnthropicContentBlock block : blocks) {
            if (block instanceof AnthropicTextContent textBlock) {
                if (textBlock.getText() != null) {
                    textBuilder.append(textBlock.getText());
                }
            } else if (block instanceof AnthropicThinkingContent thinkingBlock) {
                if (thinkingBlock.getThinking() != null) {
                    reasoningBuilder.append(thinkingBlock.getThinking());
                }
            } else if (block instanceof AnthropicToolUseContent toolUse) {
                ChatToolRequest toolRequest = new ChatToolRequest();
                toolRequest.setId(toolUse.getId());
                toolRequest.setName(toolUse.getName());
                if (toolUse.getInput() != null) {
                    try {
                        toolRequest.setArguments(objectMapper.writeValueAsString(toolUse.getInput()));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize tool input: {}", e.getMessage());
                        toolRequest.setArguments("{}");
                    }
                } else {
                    toolRequest.setArguments("{}");
                }
                toolCalls.add(toolRequest);
            }
        }

        assistantMessage.text(textBuilder.toString());
        assistantMessage.setReasoningContent(
                reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null);
        assistantMessage.setToolCalls(toolCalls);

        messages.add(assistantMessage);
        return messages;
    }
}
