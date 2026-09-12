package com.fishsunny.assistant.engine.adapter.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIRequest;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIResponse;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesContentPart;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesItem;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesToolRegister;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterFunction;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterParameter;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterProperty;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI Responses API（{@code POST /v1/responses}）适配器基类。
 *
 * <p>与 Chat Completions 形状的几处结构性差异都在这里消化：
 * <ul>
 *   <li>系统提示词走顶层 {@code instructions}，messages 里不出现 system；</li>
 *   <li>{@code input} 是平级 item 列表——工具调用是 assistant message 的<b>兄弟</b> item，
 *       工具结果也是独立 item，都不嵌套在 message 里；</li>
 *   <li>工具声明是扁平的（没有 Chat Completions 那层 {@code function} 子对象）；</li>
 *   <li>{@code store=false} 无状态：本项目自己落库并每轮全量重发，不用服务端的会话状态。</li>
 * </ul>
 *
 * <p>请求组装放在基类（两条子类只差 {@link #streaming()}），响应解析则因流式/非流式
 * 形态完全不同而各自实现。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public abstract class ResponsesBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(ResponsesBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    /**
     * store=false 时思考内容不落服务端，靠这个 include 换取加密思考串（reasoning item 的
     * {@code encrypted_content}），否则思考只在响应里出现一次、拿不回来。
     */
    protected static final String INCLUDE_ENCRYPTED_REASONING = "reasoning.encrypted_content";

    /** 未显式配置 reasoning_effort 时的默认档，也是 Responses API 自身的默认值 */
    private static final String EFFORT_MEDIUM = "medium";

    public ResponsesBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    /** 本适配器是否走流式，决定请求体的 {@code stream} 字段 */
    protected abstract boolean streaming();

    // ==================== 建连 ====================

    @Override
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        HttpRequest httpRequest = withCustomHeaders(HttpRequest.newBuilder()
                .uri(URI.create(super.baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + super.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))))
                .build();

        HttpResponse<Stream<String>> response = super.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            try (Stream<String> bodyStream = response.body()) {
                String errorMessage = bodyStream.collect(Collectors.joining("\n"));
                log.info("Responses API error: {}", errorMessage);
                throw new RuntimeException("Invalid status code: " + response.statusCode() + ", error: " + errorMessage);
            }
        } else {
            return response.body();
        }
    }

    // ==================== 请求组装 ====================

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        ChatSettings settings = chatRequest.getSettings();

        ResponsesAIRequest responsesRequest = new ResponsesAIRequest()
                .setModel(settings.getModel())
                .setInstructions(extractInstructions(chatRequest.getMessages()))
                .setInput(convertToResponsesItems(chatRequest.getMessages()))
                .setStream(streaming())
                .setStore(false)
                .setMax_output_tokens(settings.getMax_tokens())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setTools(convertTools(chatRequest.getTools()));

        ResponsesAIRequest.Reasoning reasoning = buildReasoning(settings);
        responsesRequest.setReasoning(reasoning);
        // 只有真的要思考时才索要加密串，不给服务端白干活的机会
        if (reasoning != null) {
            responsesRequest.setInclude(List.of(INCLUDE_ENCRYPTED_REASONING));
        }

        responsesRequest.setExtraBody(settings.getCustomFields());
        return responsesRequest;
    }

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * 提取系统提示词。Responses 没有 system 角色，系统提示走顶层 instructions，messages 里不再出现。
     */
    protected String extractInstructions(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (!"system".equals(message.getRole())) {
                continue;
            }
            String text = message.resolveText();
            if (StringUtils.hasText(text)) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append(text);
            }
        }
        return !builder.isEmpty() ? builder.toString() : null;
    }

    /**
     * 把内部消息列表摊平成扁平 item 列表。
     * <p>关键差异：assistant 的工具调用、tool 的返回结果都是 message 的<b>平级兄弟</b> item，
     * 不像 Chat Completions 那样嵌在 message 的 tool_calls / 单独的 tool 角色消息里。
     * <p>思考内容（reasoningContent）在 v1 <b>不回传</b>——回传需要持久化 reasoning item 的 id，
     * 那是独立后续；此处只做采集与展示。
     */
    protected List<ResponsesItem> convertToResponsesItems(List<ChatMessage> messages) {
        List<ResponsesItem> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case "system":
                    // 已提到顶层 instructions
                    break;
                case "user":
                    items.add(ResponsesItem.inputMessage("user", toUserContent(message.getContents())));
                    break;
                case "assistant": {
                    String text = plainText(message);
                    if (StringUtils.hasText(text)) {
                        items.add(ResponsesItem.inputMessage("assistant",
                                List.of(ResponsesContentPart.outputText(text))));
                    }
                    for (ChatToolRequest toolRequest : message.getToolCalls()) {
                        items.add(ResponsesItem.functionCall(
                                toolRequest.getId(),
                                toolRequest.getName(),
                                StringUtils.hasText(toolRequest.getArguments()) ? toolRequest.getArguments() : "{}"));
                    }
                    break;
                }
                case "tool": {
                    warnDroppedNonText(message, "工具结果");
                    items.add(ResponsesItem.functionCallOutput(message.getToolCallId(), plainText(message)));
                    break;
                }
                default:
                    throw new RuntimeException("Invalid role for Responses: " + message.getRole());
            }
        }
        return items;
    }

    /**
     * 转换 message 的 content 分片。{@code input_image} 同时接受 data URI 与 http(s) 直链，
     * 所以图片无需像 Anthropic / Gemini 那样转 base64。
     */
    protected List<ResponsesContentPart> toUserContent(List<MessageContent> contents) {
        List<ResponsesContentPart> parts = new ArrayList<>();
        if (contents == null) {
            return parts;
        }
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                if (textContent.getContent() != null) {
                    parts.add(ResponsesContentPart.inputText(textContent.getContent()));
                }
            } else if (content instanceof ImageContent imageContent) {
                if (StringUtils.hasText(imageContent.getUrl())) {
                    parts.add(ResponsesContentPart.inputImage(imageContent.getUrl()));
                }
            } else {
                log.warn("Responses: 暂不支持的消息内容类型，已跳过: {}", content.getClass().getSimpleName());
            }
        }
        return parts;
    }

    /**
     * 只拼文本分片、不加分隔符。
     * <p>不能用 {@link ChatMessage#resolveText()}——它在每个非末尾文本分片后追加 {@code "\n\n"}，
     * 对「文本 + 图片」的工具结果会多出尾随换行。
     */
    protected String plainText(ChatMessage message) {
        StringBuilder builder = new StringBuilder();
        List<MessageContent> contents = message.getContents();
        if (contents != null) {
            for (MessageContent content : contents) {
                if (content instanceof TextContent textContent && textContent.getContent() != null) {
                    builder.append(textContent.getContent());
                }
            }
        }
        return builder.toString();
    }

    /**
     * {@code function_call_output.output} 只能是字符串，非文本分片（图片/音频/视频/文件）无处安放，
     * warn 出来让这次能力损失可见。
     */
    private void warnDroppedNonText(ChatMessage message, String where) {
        List<MessageContent> contents = message.getContents();
        if (contents == null) {
            return;
        }
        for (MessageContent content : contents) {
            if (!(content instanceof TextContent)) {
                log.warn("Responses: {} 的 {} 类型内容无处安放（output 只能是字符串），已丢弃",
                        where, content.getClass().getSimpleName());
            }
        }
    }

    /**
     * 工具声明转扁平结构。无工具时返回 null——空数组会被严格的服务端拒。
     */
    protected List<ResponsesToolRegister> convertTools(List<StandardToolRegister> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return null;
        }
        List<ResponsesToolRegister> converted = new ArrayList<>();
        for (StandardToolRegister tool : tools) {
            StandardToolRegisterFunction function = tool.getFunction();
            if (function == null || !StringUtils.hasText(function.getName())) {
                continue;
            }
            ResponsesToolRegister register = new ResponsesToolRegister()
                    .setName(function.getName())
                    .setDescription(function.getDescription());
            if (function.getParameters() != null) {
                register.setParameters(toJsonSchema(function.getParameters(), function.getName()));
            }
            converted.add(register);
        }
        return converted.isEmpty() ? null : converted;
    }

    private Map<String, Object> toJsonSchema(StandardToolRegisterParameter params, String toolName) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (!CollectionUtils.isEmpty(params.getProperties())) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (Map.Entry<String, StandardToolRegisterProperty> entry : params.getProperties().entrySet()) {
                properties.put(entry.getKey(), toSchema(entry.getValue(), toolName, entry.getKey()));
            }
            schema.put("properties", properties);
        }

        if (!CollectionUtils.isEmpty(params.getRequired())) {
            schema.put("required", params.getRequired());
        }
        return schema;
    }

    /**
     * 递归转换 schema 节点。
     * <p>与 Gemini 有两处不同：类型名保持项目内原本的小写（OpenAI 形状本来就是小写，
     * Gemini 才需要转大写枚举）；array 缺 items 时只告警、不注入兜底 schema（OpenAI 容忍缺省，
     * Gemini 则强制要求，那边才必须补）。
     */
    private Map<String, Object> toSchema(StandardToolRegisterProperty property, String toolName, String path) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (StringUtils.hasText(property.getType())) {
            schema.put("type", property.getType());
        }
        if (StringUtils.hasText(property.getDescription())) {
            schema.put("description", property.getDescription());
        }
        if (!CollectionUtils.isEmpty(property.getProperties())) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, StandardToolRegisterProperty> entry : property.getProperties().entrySet()) {
                nested.put(entry.getKey(), toSchema(entry.getValue(), toolName, path + "." + entry.getKey()));
            }
            schema.put("properties", nested);
        }
        if (!CollectionUtils.isEmpty(property.getRequired())) {
            schema.put("required", property.getRequired());
        }
        if ("array".equals(property.getType())) {
            if (property.getItems() != null) {
                schema.put("items", toSchema(property.getItems(), toolName, path + "[]"));
            } else {
                log.warn("Responses: 工具 {} 的参数 {} 声明为 array 却没给 items，建议补声明", toolName, path);
            }
        }
        return schema;
    }

    /**
     * 思考档位。customFields 里显式给了 reasoning 就用用户的；否则以「思考模式」开关为总闸——
     * 关时整个字段省略（{@code {"effort":"minimal"}} 在不支持推理的模型上会直接 400）。
     */
    protected ResponsesAIRequest.Reasoning buildReasoning(ChatSettings settings) {
        Map<String, Object> customFields = settings.getCustomFields();
        Object configured = customFields == null ? null : customFields.get("reasoning");
        if (configured instanceof Map<?, ?> map) {
            Object effort = map.get("effort");
            if (effort instanceof String text && StringUtils.hasText(text)) {
                return new ResponsesAIRequest.Reasoning().setEffort(text);
            }
        }

        if (!Boolean.TRUE.equals(settings.getThinking())) {
            return null;
        }
        return new ResponsesAIRequest.Reasoning().setEffort(resolveEffort(settings.getReasoning_effort()));
    }

    /** max 在 Responses 里没有对应档位，落到最高档 high——不臆造 xhigh */
    private String resolveEffort(String reasoningEffort) {
        if (!StringUtils.hasText(reasoningEffort)) {
            return EFFORT_MEDIUM;
        }
        return switch (reasoningEffort.trim().toLowerCase(Locale.ROOT)) {
            case "minimal" -> "minimal";
            case "low" -> "low";
            case "high" -> "high";
            case "max" -> "high";
            default -> EFFORT_MEDIUM;
        };
    }

    // ==================== 响应解析 ====================

    /** 从 output item 列表里拆出的三样东西 */
    protected record ParsedOutput(String text, String reasoning, String reasoningSignature,
                                  List<ToolCall> toolCalls) {
    }

    /**
     * 遍历 output item 列表，抽取文本、思考摘要、思考签名与工具调用。
     */
    protected ParsedOutput parseOutput(List<ResponsesItem> output) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String signature = null;
        List<ToolCall> toolCalls = new ArrayList<>();

        if (output != null) {
            for (ResponsesItem item : output) {
                if (item == null || item.getType() == null) {
                    continue;
                }
                switch (item.getType()) {
                    case ResponsesItem.TYPE_MESSAGE -> {
                        if (item.getContent() != null) {
                            for (ResponsesContentPart part : item.getContent()) {
                                if (part != null && ResponsesContentPart.TYPE_OUTPUT_TEXT.equals(part.getType())
                                        && part.getText() != null) {
                                    text.append(part.getText());
                                }
                            }
                        }
                    }
                    case ResponsesItem.TYPE_REASONING -> {
                        if (item.getSummary() != null) {
                            for (ResponsesContentPart part : item.getSummary()) {
                                if (part != null && part.getText() != null) {
                                    reasoning.append(part.getText());
                                }
                            }
                        }
                        // 一个响应可能带多个 reasoning item，取最后一个加密串（与 Anthropic 签名同口径）
                        if (StringUtils.hasText(item.getEncrypted_content())) {
                            signature = item.getEncrypted_content();
                        }
                    }
                    case ResponsesItem.TYPE_FUNCTION_CALL -> {
                        ToolCall.Function function = new ToolCall.Function()
                                .setName(item.getName())
                                .setArguments(StringUtils.hasText(item.getArguments()) ? item.getArguments() : "{}");
                        // 必须用 call_id：只有它能把 function_call_output 关联回调用，
                        // item.id 是服务端内部标识（见 ChatProcessor 里 toolcall.getId() 的用途）
                        toolCalls.add(new ToolCall().setId(item.getCall_id()).setFunction(function));
                    }
                    default -> log.debug("Responses: 忽略未处理的 output item 类型: {}", item.getType());
                }
            }
        }
        return new ParsedOutput(text.toString(), reasoning.toString(), signature, toolCalls);
    }

    /** 非流式响应 → 内部消息列表（流式的末帧由子类自行拼装） */
    protected List<ChatMessage> convertOutputToMessages(ResponsesAIResponse response) {
        ParsedOutput parsed = parseOutput(response.getOutput());
        ChatMessage assistantMessage = new ChatMessage()
                .setRole("assistant")
                .setReasoningContent(parsed.reasoning().isEmpty() ? null : parsed.reasoning())
                .setToolCalls(ChatToolRequest.convert(parsed.toolCalls()));
        assistantMessage.text(parsed.text());
        return List.of(assistantMessage);
    }

    /**
     * 把失败/不完整的响应转成可读异常。
     * <p>需要它是因为 Responses 有「HTTP 200 但内容不可用」的形态：被拦、超输出上限、模型空转。
     */
    protected RuntimeException readableFailure(ResponsesAIResponse response) {
        return new RuntimeException(describeFailure(response));
    }

    /** 失败原因的纯文本描述，异常与流式 STATUS_ERROR 帧共用同一份措辞 */
    protected String describeFailure(ResponsesAIResponse response) {
        if (response == null) {
            return "Responses API 未返回响应体";
        }
        if (response.hasError()) {
            return "Responses API 返回错误: " + nodeMessage(response.getError());
        }
        JsonNode incomplete = response.getIncomplete_details();
        if (incomplete != null && !incomplete.isNull()) {
            return "Responses 响应未完成: " + nodeMessage(incomplete);
        }
        return "Responses 未返回任何 output（status=" + response.getStatus() + "）";
    }

    private String nodeMessage(JsonNode node) {
        JsonNode message = node.get("message");
        if (message == null || message.isNull()) {
            message = node.get("reason");
        }
        return message != null && !message.isNull() ? message.asText() : node.toString();
    }
}
