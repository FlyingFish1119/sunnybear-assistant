package com.fishsunny.assistant.engine.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.tts.TTSClient;
import com.fishsunny.assistant.utils.EnvResolver;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
public abstract class AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AIAdapter.class);

    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    /** 共享 HttpClient，避免每次请求都 newHttpClient() */
    protected final HttpClient httpClient;

    protected String baseUrl;

    /** 模型列表端点（可选）；为空表示该适配器不提供自动获取模型列表的能力 */
    protected String modelUrl;

    protected String apiKey;

    /** 自定义请求头，建立连接时随请求带上；协议基础头（Content-Type/鉴权）由 {@link #protocolHeaders()} 提供 */
    protected Map<String, String> headers;

    /** 工厂注入的 TTS 客户端（engine.tts.enable 关闭时为 null）；实现 HandleTTSAble 的适配器判空后使用 */
    protected final TTSClient ttsClient;

    protected Class<? extends AIRequest> masterReqCls;

    protected Class<? extends AIRequest> targetReqCls;

    protected Class<? extends AIResponse> masterRespCls;

    protected Class<? extends AIResponse> targetRespCls;

    public abstract AIRequest convertToTarget(AIRequest request);

    public abstract AIRequest convertToMaster(AIRequest request);

    public abstract AIResponse convertToTarget(AIResponse response);

    public abstract AIResponse convertToMaster(AIResponse response);

    /**
     * 建连：默认以 {@link #buildRequestUrl} 为端点 POST JSON，带上 {@link #requestHeaders()}。
     * 各协议只在 {@link #protocolHeaders()} / {@link #buildRequestUrl} 上有差异，无需各自重写本方法。
     */
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(buildRequestUrl(request)))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)));
        requestHeaders().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            try (Stream<String> bodyStream = response.body()) {
                String errorMessage = bodyStream.collect(Collectors.joining("\n"));
                log.info("{} API error: {}", getClass().getSimpleName(), errorMessage);
                throw new RuntimeException("Invalid status code: " + response.statusCode() + ", error: " + errorMessage);
            }
        }
        return response.body();
    }

    /** 请求端点，默认 baseUrl；需要把参数（如 Gemini 的模型名）拼进 URL 的协议覆写本方法 */
    protected String buildRequestUrl(AIRequest request) {
        return baseUrl;
    }

    public final Stream<String> connect(AIRequest request) throws Exception {
        if (request == null) {
            throw new Exception("null request");
        }
        return establishHttpClient(convertToTarget(request));
    }

    public abstract boolean finished(AIResponse response);

    public abstract boolean collectChunk(AIResponse response);

    public abstract List<ToolCall> getToolCalls();
    public abstract String getReasoning();
    public abstract String getContent();

    /** Anthropic extended thinking 推理签名，非 Anthropic 适配器返回 null */
    public String getReasoningSignature() { return null; }

    public abstract void checkCls(Class<? extends AIRequest> masterCls,
                                  Class<? extends AIRequest> targetCls,
                                  Class<? extends AIResponse> masterRespCls,
                                  Class<? extends AIResponse> targetRespCls) throws Exception;

    public AIAdapter(AIAdapterOption option) throws Exception {
        this.baseUrl = option.getBaseUrl();
        this.modelUrl = option.getModelUrl();
        this.apiKey = EnvResolver.resolve(option.getApiKey());
        this.headers = option.getHeaders();
        this.masterReqCls = option.getMasterReqCls();
        this.targetReqCls = option.getTargetReqCls();
        this.masterRespCls = option.getMasterRespCls();
        this.targetRespCls = option.getTargetRespCls();
        this.httpClient = option.getHttpClient();
        this.ttsClient = option.getTtsClient();
        checkCls(masterReqCls, targetReqCls, masterRespCls, targetRespCls);
    }

    /**
     * 协议基础请求头（Content-Type / 鉴权等），各协议子类按需覆写。
     */
    protected Map<String, String> protocolHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey);
    }

    /**
     * 一次请求实际要带的完整请求头：协议基础头 + 配置的自定义头（同名以自定义为准）。
     * 建连（establishHttpClient）与拉取模型列表（listModels）共用。
     */
    protected Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        protocolHeaders().forEach((name, value) -> {
            if (StringUtils.hasText(value)) {
                headers.put(name, value);
            }
        });
        if (this.headers != null) {
            this.headers.forEach((name, value) -> {
                if (value != null) {
                    headers.put(name, value);
                }
            });
        }
        return headers;
    }

    /**
     * 请求 modelUrl 获取可选模型列表；未配置 modelUrl 时返回空列表。
     */
    public List<ModelInfo> listModels() throws Exception {
        if (!StringUtils.hasText(modelUrl)) {
            return List.of();
        }
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders());
        headers.putIfAbsent("Accept", "application/json");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(modelUrl.trim()))
                .GET();
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("获取模型列表失败，HTTP " + response.statusCode());
        }
        return extractModels(response.body());
    }

    /**
     * 从模型列表响应中提取模型条目，兼容常见形态：
     * OpenAI 系 {@code data[].id}、Gemini {@code models[].name}（会去掉 {@code models/} 前缀），
     * 以及顶层数组 / 纯字符串。除 id 外其余原始字段原样收进 details，不做字段映射。
     */
    protected List<ModelInfo> extractModels(String body) throws JsonProcessingException {
        JsonNode array = objectMapper.readTree(body);
        if (!array.isArray()) {
            array = array.path("data");
        }
        if (!array.isArray()) {
            array = array.path("models");
        }
        if (!array.isArray()) {
            return List.of();
        }
        List<ModelInfo> models = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            String id = node.isTextual() ? node.asText() : firstText(node, "id", "name");
            if (!StringUtils.hasText(id)) {
                continue;
            }
            if (id.startsWith("models/")) {
                id = id.substring("models/".length());
            }
            if (!seen.add(id)) {
                continue;
            }
            models.add(new ModelInfo().setId(id).setDetails(extractDetails(node, id)));
        }
        return models;
    }

    /** 收集条目除 id 外的原始字段；与 id 重复的字段（含 Gemini 的 models/ 前缀形态）跳过 */
    private Map<String, Object> extractDetails(JsonNode node, String id) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (!node.isObject()) {
            return details;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                return;
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (text.equals(id) || text.equals("models/" + id)) {
                    return;
                }
                details.put(entry.getKey(), text);
                return;
            }
            // 嵌套对象/数组转成紧凑 JSON，前端按字符串展示
            details.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return details;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * 该类为适配器使用的工具调用类
     */
    @Data
    @Accessors(chain = true)
    public static class ToolCall {
        private Integer index;
        private String id;
        private final String type = "function";
        private Function function;

        @Data
        @Accessors(chain = true)
        public static class Function {
            private String name;
            private String arguments;
        }

        public ToolCall() {
        }
    }
}
