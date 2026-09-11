package com.fishsunny.assistant.engine.adapter.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIRequest;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiGenerationConfig;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiPromptFeedback;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiThinkingConfig;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiCandidate;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiContent;
import com.fishsunny.assistant.engine.protocol.gemini.message.content.*;
import com.fishsunny.assistant.engine.protocol.gemini.tools.GeminiFunctionDeclaration;
import com.fishsunny.assistant.engine.protocol.gemini.tools.GeminiTool;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterFunction;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterParameter;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterProperty;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gemini（generativelanguage.googleapis.com 原生协议）适配器基类。
 *
 * <p>与 OpenAI / Anthropic 的几处硬性差异，都在这里消化：
 * <ul>
 *   <li>鉴权头是 {@code x-goog-api-key}，不是 Authorization: Bearer；</li>
 *   <li>模型名拼在 URL 路径上（{@code /v1beta/models/{model}:generateContent}），
 *       因此 baseUrl 应配到 {@code .../v1beta/models} 为止；</li>
 *   <li>角色只有 user / model——系统提示词走顶层 systemInstruction，
 *       模型侧的工具调用是 model 里的 functionCall part，工具结果则是 user 里的 functionResponse part；</li>
 *   <li>{@code functionResponse.response} 必须是 JSON 对象，字符串结果要包一层。</li>
 * </ul>
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public abstract class GeminiBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(GeminiBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    /**
     * 自造 tool call id 的前缀。模型没下发 id 时（2.5 系列常见）由适配器合成，
     * 回传历史时凭前缀识别并省略——把客户端自造的 id 发给服务端会对不上。
     */
    protected static final String SYNTHETIC_CALL_ID_PREFIX = "gemini_call_";

    /** 思考档位：Pro / Flash 都支持的最低档（minimal 只有 Flash 认） */
    private static final String THINKING_LEVEL_LOW = "low";

    /** 思考档位：未显式配置 reasoning_effort 时用的默认档（也是 3.x 自身的默认值） */
    private static final String THINKING_LEVEL_HIGH = "high";

    /** 工具消息既没带 name、上下文里也查不到时的兜底函数名（Gemini 要求 name 必填） */
    private static final String FALLBACK_FUNCTION_NAME = "unknown_function";

    /** 安全过滤阈值：无条件放行（最低档）。比 OFF 兼容性更好——OFF 是 2.5 起才作为默认值 */
    private static final String SAFETY_THRESHOLD_OFF = "BLOCK_NONE";

    /**
     * 默认关掉安全过滤的分类。只列各代模型都认的四个经典分类：
     * HARM_CATEGORY_CIVIC_INTEGRITY / HARM_CATEGORY_JAILBREAK 等新分类发给不支持的模型会直接 400，
     * 而 2.5 之后未指定的分类本来就默认关着，漏掉它们没有代价。
     * <p>注意放行的是概率过滤，Google 的硬性底线（如未成年人相关）不受此影响。
     */
    private static final List<String> SAFETY_CATEGORIES = List.of(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT");

    public GeminiBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    /**
     * 端点后缀，流式与非流式的唯一差异（{@code :generateContent} / {@code :streamGenerateContent?alt=sse}）。
     */
    protected abstract String endpointSuffix();

    // ==================== 建连 ====================

    @Override
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        String url = buildUrl(((GeminiAIRequest) request).getModel());

        HttpRequest httpRequest = withCustomHeaders(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", super.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))))
                .build();

        HttpResponse<Stream<String>> response = super.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            try (Stream<String> bodyStream = response.body()) {
                String errorMessage = bodyStream.collect(Collectors.joining("\n"));
                log.info("Gemini API error: {}", errorMessage);
                throw new RuntimeException("Invalid status code: " + response.statusCode() + ", error: " + errorMessage);
            }
        } else {
            return response.body();
        }
    }

    /**
     * 拼接请求地址：{baseUrl}/{model}:{action}。baseUrl 需配到 {@code .../v1beta/models}，
     * model 允许写成 {@code models/gemini-2.5-flash} 或 {@code gemini-2.5-flash}。
     */
    private String buildUrl(String model) {
        if (!StringUtils.hasText(super.baseUrl)) {
            throw new RuntimeException("Gemini baseUrl 未配置（应形如 https://generativelanguage.googleapis.com/v1beta/models）");
        }
        String base = super.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String name = model == null ? "" : model.trim();
        if (name.startsWith("models/")) {
            name = name.substring("models/".length());
        }
        if (!StringUtils.hasText(name)) {
            throw new RuntimeException("Gemini 请求缺少模型名（模型名需拼进 URL 路径）");
        }
        return base + "/" + name + endpointSuffix();
    }

    // ==================== 请求转换：消息 ====================

    /**
     * 把内部 ChatMessage 列表转成 Gemini contents。
     *
     * <p>两处与 Anthropic 不同的处理：
     * <ul>
     *   <li>工具结果（role=tool）要攒成一批，作为**一个** user content 下发；</li>
     *   <li>相邻同角色的 content 必须合并——Gemini 要求 user / model 严格交替，
     *       而工具结果（user）后面紧跟用户发言（user）在项目里是常态。</li>
     * </ul>
     */
    protected List<GeminiContent> convertToGeminiContents(List<ChatMessage> messages) {
        List<GeminiContent> contents = new ArrayList<>();
        List<GeminiPart> pendingToolResponses = new ArrayList<>();
        // toolCallId -> 函数名：role=tool 的消息不一定带 name，从上一轮 assistant 的 toolCalls 里补
        Map<String, String> toolNames = new HashMap<>();

        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case ChatMessage.ROLE_SYSTEM -> {
                    // 系统提示词走顶层 systemInstruction，见 extractSystemInstruction
                }
                case ChatMessage.ROLE_USER -> {
                    flushToolResponses(contents, pendingToolResponses);
                    appendContent(contents, GeminiContent.user(convertContentsToParts(message.getContents())));
                }
                case ChatMessage.ROLE_ASSISTANT -> {
                    flushToolResponses(contents, pendingToolResponses);
                    appendContent(contents, GeminiContent.model(convertAssistantParts(message, toolNames)));
                }
                case ChatMessage.ROLE_TOOL -> pendingToolResponses.add(
                        GeminiPart.functionResponse(buildFunctionResponse(message, toolNames)));
                default -> throw new RuntimeException("Invalid role for Gemini: " + message.getRole());
            }
        }
        flushToolResponses(contents, pendingToolResponses);
        return contents;
    }

    /** 攒下的工具结果合并成一个 user content 下发（同一轮多个工具调用必须回在一条里） */
    private void flushToolResponses(List<GeminiContent> contents, List<GeminiPart> pending) {
        if (pending.isEmpty()) {
            return;
        }
        appendContent(contents, GeminiContent.user(new ArrayList<>(pending)));
        pending.clear();
    }

    /** 追加一轮内容；与上一轮角色相同时合并 parts（Gemini 要求 user / model 交替） */
    private void appendContent(List<GeminiContent> contents, GeminiContent content) {
        if (content == null || !content.hasParts()) {
            return;
        }
        GeminiContent last = contents.isEmpty() ? null : contents.getLast();
        if (last != null && Objects.equals(last.getRole(), content.getRole())) {
            last.getParts().addAll(content.getParts());
            return;
        }
        contents.add(content);
    }

    /**
     * 系统提示词：Gemini 没有 system 角色，多条 system 消息拼成一个顶层 systemInstruction。
     */
    protected GeminiContent extractSystemInstruction(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (ChatMessage.ROLE_SYSTEM.equals(message.getRole())) {
                String text = message.resolveText();
                if (StringUtils.hasText(text)) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.isEmpty() ? null : new GeminiContent().setParts(List.of(GeminiPart.text(sb.toString())));
    }

    /**
     * assistant 消息转 parts：思考摘要 + 正文 + 工具调用，并把推理签名放回它原本所在的 part
     * （有工具调用时挂 functionCall part，否则挂第一个 part）。
     */
    private List<GeminiPart> convertAssistantParts(ChatMessage message, Map<String, String> toolNames) {
        List<GeminiPart> parts = new ArrayList<>();
        String signature = message.resolveReasoningSignature();

        // 思考摘要只在拿得到签名时才回传：Gemini 3 会校验签名，缺签名的 thought part 可能
        // 直接让整个请求失败（MISSING_THOUGHT_SIGNATURE），宁可少给一段思考上下文，
        // 也不能让请求挂掉
        if (StringUtils.hasText(message.getReasoningContent()) && StringUtils.hasText(signature)) {
            parts.add(GeminiPart.thought(message.getReasoningContent(), null));
        }
        String text = message.resolveText();
        if (StringUtils.hasText(text)) {
            parts.add(GeminiPart.text(text));
        }

        for (ChatToolRequest toolRequest : message.getToolCalls()) {
            toolNames.put(toolRequest.getId(), toolRequest.getName());
            GeminiFunctionCall call = new GeminiFunctionCall()
                    .setName(toolRequest.getName())
                    .setArgs(parseArguments(toolRequest.getArguments()));
            // 自造的 id 不回传，服务端会对不上
            String id = toolRequest.getId();
            if (StringUtils.hasText(id) && !id.startsWith(SYNTHETIC_CALL_ID_PREFIX)) {
                call.setId(id);
            }
            parts.add(GeminiPart.functionCall(call));
        }

        if (StringUtils.hasText(signature) && !parts.isEmpty()) {
            parts.stream()
                    .filter(part -> part.getFunctionCall() != null)
                    .findFirst()
                    .orElse(parts.getFirst())
                    .setThoughtSignature(signature);
        }
        return parts;
    }

    /**
     * 多模态内容转 parts：data URI 直接内联；其余 URL 形式 Gemini 不收（fileData 只认 File API / gs://），跳过并告警。
     */
    protected List<GeminiPart> convertContentsToParts(List<MessageContent> contents) {
        List<GeminiPart> parts = new ArrayList<>();
        if (CollectionUtils.isEmpty(contents)) {
            return parts;
        }
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                if (textContent.getContent() != null) {
                    parts.add(GeminiPart.text(textContent.getContent()));
                }
            } else if (content instanceof ImageContent imageContent) {
                addMediaPart(parts, imageContent.getUrl(), "图片");
            } else if (content instanceof VideoContent videoContent) {
                addMediaPart(parts, videoContent.getUrl(), "视频");
            } else if (content instanceof AudioContent audioContent) {
                addMediaPart(parts, audioContent.getUrl(), "音频");
            } else if (content instanceof FileContent fileContent) {
                addMediaPart(parts, fileContent.getUrl(), "文件");
            }
        }
        return parts;
    }

    private void addMediaPart(List<GeminiPart> parts, String url, String kind) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        if (!url.startsWith("data:")) {
            log.warn("Gemini 适配器：{}地址非 data URI，暂不支持（fileData 只接受 File API / gs:// 地址），已跳过：{}", kind, url);
            return;
        }
        String base64Data = Base64Utils.extractBase64FromDataUri(url);
        String extension = Base64Utils.getExtensionFromDataUri(url);
        String mimeType = Base64Utils.getMimeTypeByExtension(extension);
        if (base64Data == null || mimeType == null) {
            log.warn("Gemini 适配器：无法解析{}的 data URI，已跳过：{}", kind, url);
            return;
        }
        parts.add(GeminiPart.inlineData(new GeminiInlineData(mimeType, base64Data)));
    }

    // ==================== 请求转换：工具与生成配置 ====================

    /**
     * 工具声明转 Gemini 格式。无工具时返回 null——空 tools 数组会被 API 拒。
     */
    protected List<GeminiTool> convertTools(List<StandardToolRegister> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return null;
        }
        List<GeminiFunctionDeclaration> declarations = new ArrayList<>();
        for (StandardToolRegister tool : tools) {
            StandardToolRegisterFunction function = tool.getFunction();
            if (function == null || !StringUtils.hasText(function.getName())) {
                continue;
            }
            GeminiFunctionDeclaration declaration = new GeminiFunctionDeclaration()
                    .setName(function.getName())
                    .setDescription(function.getDescription());
            if (function.getParameters() != null) {
                declaration.setParameters(convertParametersToSchema(function.getParameters(), function.getName()));
            }
            declarations.add(declaration);
        }
        return declarations.isEmpty() ? null : List.of(GeminiTool.of(declarations));
    }

    private Map<String, Object> convertParametersToSchema(StandardToolRegisterParameter params, String toolName) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");

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
     * 递归转换 schema 节点（对象字段、数组元素都会走到这里）。
     * <p>array 一定补上 items：Gemini 对 ARRAY 类型强制要求 items，缺了整轮请求 400
     * （OpenAI 容忍缺省，所以项目里原有的数组参数声明都没写 items）。
     */
    private Map<String, Object> toSchema(StandardToolRegisterProperty property, String toolName, String path) {
        Map<String, Object> schema = new LinkedHashMap<>();
        // 项目内的工具声明沿用 OpenAI 的小写类型名（string/integer/...），
        // Gemini 的 Schema.type 是枚举，统一转大写（小写虽多数情况能过，大写才是文档形态）
        if (StringUtils.hasText(property.getType())) {
            schema.put("type", property.getType().toUpperCase(Locale.ROOT));
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
        if ("ARRAY".equals(schema.get("type"))) {
            if (property.getItems() != null) {
                schema.put("items", toSchema(property.getItems(), toolName, path + "[]"));
            } else {
                // 工具没声明元素结构时的兜底：给个可能不精确的 schema，
                // 也好过整轮请求被拒（连带其它工具一起用不了）
                log.warn("Gemini: 工具 {} 的参数 {} 声明为 array 却没给 items，按字符串数组兜底，建议补声明", toolName, path);
                schema.put("items", Map.of("type", "STRING"));
            }
        }
        return schema;
    }

    /**
     * 安全过滤默认全放行，避免正常内容被概率过滤误拦。customFields 里显式给了 safetySettings
     * 就用用户的（想收紧某几个分类时直接配，不必改代码）。
     */
    protected List<Map<String, Object>> buildSafetySettings(Map<String, Object> customFields) {
        Object configured = customFields == null ? null : customFields.get("safetySettings");
        if (configured instanceof List<?> list) {
            List<Map<String, Object>> settings = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    settings.add(typed);
                }
            }
            if (!settings.isEmpty()) {
                return settings;
            }
        }

        List<Map<String, Object>> settings = new ArrayList<>();
        for (String category : SAFETY_CATEGORIES) {
            settings.add(Map.of("category", category, "threshold", SAFETY_THRESHOLD_OFF));
        }
        return settings;
    }

    protected GeminiGenerationConfig buildGenerationConfig(ChatSettings settings) {
        GeminiGenerationConfig config = new GeminiGenerationConfig()
                .setTemperature(settings.getTemperature())
                .setTopP(settings.getTop_p())
                .setMaxOutputTokens(settings.getMax_tokens());
        // response_format 只有 type（json_object / json_schema），对应的都是"输出 JSON"这一件事
        if (settings.getResponse_format() != null && StringUtils.hasText(settings.getResponse_format().getType())) {
            config.setResponseMimeType("application/json");
        }
        config.setThinkingConfig(buildThinkingConfig(settings));
        return config;
    }

    /**
     * 思考配置。<b>只支持 Gemini 3.x</b>（档位制），2.5 的数字预算已随 Google 下线一并移除。
     *
     * <p>「思考模式」开关是总闸：关 = 压到最低档（少思考、少花钱），开 = 按 reasoning_effort 走，
     * 没配就取模型默认档 high。关闭时不下发 includeThoughts——摘要留着也没人看，还多占报文。
     *
     * <p>关闭为什么是 low 而不是 minimal：minimal 只有 Flash 系列认，Pro 会拒，low 是两边都支持的最低档。
     */
    protected GeminiThinkingConfig buildThinkingConfig(ChatSettings settings) {
        boolean thinkingEnabled = Boolean.TRUE.equals(settings.getThinking());

        if (!thinkingEnabled) {
            return GeminiThinkingConfig.level(THINKING_LEVEL_LOW, null);
        }
        String level = mapThinkingLevel(settings.getReasoning_effort());
        return GeminiThinkingConfig.level(level == null ? THINKING_LEVEL_HIGH : level, Boolean.TRUE);
    }

    /** 项目里的 reasoning_effort（low/high/max）映射到 Gemini 档位，无法识别时交给上层取默认档 */
    private String mapThinkingLevel(String reasoningEffort) {
        if (!StringUtils.hasText(reasoningEffort)) {
            return null;
        }
        return switch (reasoningEffort.trim().toLowerCase(Locale.ROOT)) {
            case "minimal" -> "minimal";
            case "low" -> "low";
            case "medium" -> "medium";
            case "high", "max" -> "high";
            default -> null;
        };
    }

    // ==================== 响应解析 ====================

    /**
     * 一轮响应的解析结果：正文、思考摘要、思考签名、工具调用。
     */
    protected record ParsedParts(String text, String reasoning, String thoughtSignature, List<ToolCall> toolCalls) {
    }

    /**
     * 解析 parts：thought=true 的是思考摘要（不是正文）；functionCall 转成适配器工具调用；
     * 签名取最后一个（一轮里可能挂在多个 part 上）。
     */
    protected ParsedParts parseParts(List<GeminiPart> parts) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String signature = null;
        List<ToolCall> toolCalls = new ArrayList<>();

        if (parts != null) {
            for (GeminiPart part : parts) {
                if (StringUtils.hasText(part.getThoughtSignature())) {
                    signature = part.getThoughtSignature();
                }
                if (part.getFunctionCall() != null) {
                    toolCalls.add(toToolCall(part.getFunctionCall()));
                    continue;
                }
                if (part.getText() == null) {
                    continue;
                }
                // 空白 chunk（段落分隔）也是有效正文，不能按 hasText 过滤
                if (Boolean.TRUE.equals(part.getThought())) {
                    reasoning.append(part.getText());
                } else {
                    text.append(part.getText());
                }
            }
        }
        return new ParsedParts(text.toString(), reasoning.toString(), signature, toolCalls);
    }

    private ToolCall toToolCall(GeminiFunctionCall call) {
        String id = StringUtils.hasText(call.getId())
                ? call.getId()
                : SYNTHETIC_CALL_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ToolCall.Function function = new ToolCall.Function()
                .setName(call.getName())
                .setArguments(writeArguments(call.getArgs()));
        return new ToolCall().setId(id).setFunction(function);
    }

    private String writeArguments(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            log.warn("Gemini 工具参数序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("Gemini 工具参数反序列化失败，按空参数处理: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 工具结果转 functionResponse：response 必须是 JSON 对象，
     * 结果本身是 JSON 对象时原样用，否则包一层 {"result": ...}。
     */
    private GeminiFunctionResponse buildFunctionResponse(ChatMessage message, Map<String, String> toolNames) {
        GeminiFunctionResponse response = new GeminiFunctionResponse();

        String callId = message.getToolCallId();
        if (StringUtils.hasText(callId) && !callId.startsWith(SYNTHETIC_CALL_ID_PREFIX)) {
            response.setId(callId);
        }

        String name = message.getName();
        if (!StringUtils.hasText(name)) {
            name = toolNames.get(callId);
        }
        if (!StringUtils.hasText(name)) {
            log.warn("Gemini: 工具结果 {} 找不到对应函数名，回传 {} 占位", callId, FALLBACK_FUNCTION_NAME);
            name = FALLBACK_FUNCTION_NAME;
        }
        response.setName(name);
        response.setResponse(wrapToolResult(message.resolveText()));
        return response;
    }

    private Map<String, Object> wrapToolResult(String result) {
        if (StringUtils.hasText(result)) {
            try {
                JsonNode node = objectMapper.readTree(result);
                if (node.isObject()) {
                    return objectMapper.convertValue(node, new TypeReference<>() {
                    });
                }
            } catch (JsonProcessingException ignored) {
                // 不是 JSON 文本，走下面的包装
            }
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("result", result == null ? "" : result);
        return wrapper;
    }

    /**
     * 完整响应转内部 ChatMessage（非流式整包、流式收尾都用它）。
     */
    protected List<ChatMessage> convertResponseToMessages(GeminiAIResponse response) {
        GeminiCandidate candidate = response.firstCandidate();
        if (candidate == null) {
            throw noCandidateError(response);
        }
        ParsedParts parsed = parseParts(candidate.getContent() == null ? null : candidate.getContent().getParts());

        ChatMessage message = new ChatMessage()
                .setReasoningSignature(parsed.thoughtSignature());
        message.assistant(
                parsed.text(),
                parsed.reasoning().isEmpty() ? null : parsed.reasoning(),
                parsed.toolCalls().stream()
                        .map(toolCall -> new ChatToolRequest()
                                .setId(toolCall.getId())
                                .setName(toolCall.getFunction().getName())
                                .setArguments(toolCall.getFunction().getArguments()))
                        .toList());
        warnOnAbnormalFinish(candidate);
        return List.of(message);
    }

    /**
     * finishReason 非 STOP/空 时记一条告警——被安全策略截断、思考签名缺失这类问题
     * 到这里已经无法撤销，留痕便于排查。
     */
    protected void warnOnAbnormalFinish(GeminiCandidate candidate) {
        String finishReason = candidate.getFinishReason();
        if (StringUtils.hasText(finishReason) && !GeminiCandidate.FINISH_STOP.equals(finishReason)) {
            log.warn("Gemini 本轮以 {} 结束{}", finishReason,
                    StringUtils.hasText(candidate.getFinishMessage()) ? "：" + candidate.getFinishMessage() : "");
        }
    }

    /** 被安全策略拦截时 HTTP 仍是 200，只是没有候选——给一个能看懂的错 */
    protected RuntimeException noCandidateError(GeminiAIResponse response) {
        GeminiPromptFeedback feedback = response.getPromptFeedback();
        if (feedback != null && StringUtils.hasText(feedback.getBlockReason())) {
            return new RuntimeException("Gemini 拒绝生成，blockReason=" + feedback.getBlockReason());
        }
        return new RuntimeException("Gemini 未返回任何候选内容（candidates 为空）");
    }

    // ==================== 未使用的转换方向 ====================

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        throw new RuntimeException("Not implemented");
    }
}
