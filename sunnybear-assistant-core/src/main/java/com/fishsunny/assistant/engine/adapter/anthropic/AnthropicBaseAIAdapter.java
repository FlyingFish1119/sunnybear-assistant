package com.fishsunny.assistant.engine.adapter.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicAIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicOutputConfig;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicThinking;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.*;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicAssistantMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicUserMessage;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.utils.Base64Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class AnthropicBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AnthropicBaseAIAdapter.class);

    /** Anthropic API version header value */
    protected static final String ANTHROPIC_VERSION = "2023-06-01";

    public AnthropicBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    /** Anthropic 鉴权走 x-api-key + anthropic-version，所有端点（含模型列表）一致 */
    @Override
    protected Map<String, String> protocolHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "x-api-key", apiKey == null ? "" : apiKey,
                "anthropic-version", ANTHROPIC_VERSION);
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

    // ==================== Thinking Config ====================

    /** 未配置 reasoning_effort 时的默认思考档位 */
    protected static final String DEFAULT_THINKING_EFFORT = "high";

    /**
     * 写入思考相关配置。
     *
     * <p>新版 Claude 只认 {@code thinking.type = adaptive}：旧写法 {@code enabled + budget_tokens}
     * 会被上游判为参数不支持、整轮请求 400；思考深度改由顶层 {@code output_config.effort} 控制，
     * 且不能塞进 thinking 对象里面。关闭思考时两个字段都不发。
     */
    protected void applyThinkingConfig(AnthropicAIRequest request, ChatSettings settings) {
        if (!Boolean.TRUE.equals(settings.getThinking())) {
            return;
        }
        request.setThinking(AnthropicThinking.adaptive());
        request.setOutput_config(new AnthropicOutputConfig(resolveEffort(settings.getReasoning_effort())));
    }

    /**
     * 项目里的 reasoning_effort（low / high / max）与 Anthropic 的 effort 同名同义，直接沿用；
     * 没配置或值不认识时取默认档。
     */
    private static String resolveEffort(String reasoningEffort) {
        if (!StringUtils.hasText(reasoningEffort)) {
            return DEFAULT_THINKING_EFFORT;
        }
        String effort = reasoningEffort.trim().toLowerCase();
        return switch (effort) {
            case "low", "medium", "high", "max" -> effort;
            default -> DEFAULT_THINKING_EFFORT;
        };
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

        for (ChatMessage message : messages) {
            if ("system".equals(message.getRole())) {
                // System messages are handled separately via extractSystemPrompt()
                continue;
            }
            switch (message.getRole()) {
                case "user": {
                    // Flush pending tool results before adding user message
                    flushToolResults(anthropicMessages, pendingToolResults);
                    AnthropicUserMessage userMessage = new AnthropicUserMessage();
                    userMessage.setContent(convertToAnthropicContentBlocks(message.getContents()));
                    anthropicMessages.add(userMessage);
                    break;
                }
                case "assistant": {
                    // Flush pending tool results before adding assistant message
                    flushToolResults(anthropicMessages, pendingToolResults);
                    AnthropicAssistantMessage assistantMessage = new AnthropicAssistantMessage();
                    List<AnthropicContentBlock> blocks = new ArrayList<>();
                    // reasoning / thinking text
                    // 签名回退到 extension 里落库的副本：chat_message 没有该列，会话重载后字段是空的。
                    // 拿不到签名的思考块一律不回传——空签名会被 Anthropic 判为签名无效，
                    // 整轮请求跟着挂掉；少一段思考上下文比请求失败划算（与 Gemini 适配器同一口径）
                    String sig = message.resolveReasoningSignature();
                    if (message.getReasoningContent() != null && !message.getReasoningContent().isEmpty()
                            && StringUtils.hasText(sig)) {
                        blocks.add(new AnthropicThinkingContent()
                                .setThinking(message.getReasoningContent())
                                .setSignature(sig));
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
                case "tool":
                    // Collect tool results; flush as one user message when the next
                    // non-tool message arrives (or at end). Anthropic requires all
                    // tool_result blocks for an assistant's tool_use blocks to be in
                    // a single user message immediately following the assistant.
                    pendingToolResults.add(buildToolResultContent(message));
                    break;
                default:
                    throw new RuntimeException("Invalid role for Anthropic: " + message.getRole());
            }
        }
        // Flush any remaining tool results at end
        flushToolResults(anthropicMessages, pendingToolResults);
        return anthropicMessages;
    }

    /**
     * 构造工具结果内容块。
     *
     * <p>工具结果里的图片等附件必须随 tool_result 一起回传：Anthropic 的
     * {@code tool_result.content} 允许是 content block 数组，这是 Claude 唯一能直接「看到」
     * 工具产出图片的通道。此前只回传 {@link ChatMessage#resolveText()}，图片被整块丢弃，
     * 视觉子 Agent（如 computer use 的 raw 截屏）便只能读到「图片已保存至……」这句文字。
     *
     * <p>没有非文本附件时仍退回 String 形式，兼容性最好。
     *
     * @param message role=tool 的消息，contents 首块为结果正文，其后为图片等附件
     * @return 携带文本与附件块的 tool_result
     */
    private AnthropicToolResultContent buildToolResultContent(ChatMessage message) {
        String text = message.resolveText();
        // convertToAnthropicContentBlocks 会把文本块一并转出，这里只留附件，避免正文重复
        List<AnthropicContentBlock> attachments = convertToAnthropicContentBlocks(message.getContents());
        attachments.removeIf(AnthropicTextContent.class::isInstance);
        if (attachments.isEmpty()) {
            return new AnthropicToolResultContent(message.getToolCallId(), text);
        }
        List<AnthropicContentBlock> blocks = new ArrayList<>();
        if (StringUtils.hasText(text)) {
            blocks.add(new AnthropicTextContent(text));
        }
        blocks.addAll(attachments);
        return new AnthropicToolResultContent(message.getToolCallId(), blocks);
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
            if ("system".equals(message.getRole())) {
                String text = message.resolveText();
                if (text != null && !text.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(text);
                }
            }
        }
        return !sb.isEmpty() ? sb.toString() : null;
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
        assistantMessage.setRole("assistant");

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
