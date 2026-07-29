package com.fishsunny.assistant.engine.adapter;

import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.net.http.HttpClient;
import java.util.List;
import java.util.stream.Stream;

@Getter
public abstract class AIAdapter {

    protected String baseUrl;

    protected String apiKey;

    protected Class<? extends AIRequest> masterReqCls;

    protected Class<? extends AIRequest> targetReqCls;

    protected Class<? extends AIResponse> masterRespCls;

    protected Class<? extends AIResponse> targetRespCls;

    /** 共享 HttpClient，避免每次请求都 newHttpClient() */
    protected final HttpClient httpClient;

    public abstract AIRequest convertToTarget(AIRequest request);

    public abstract AIRequest convertToMaster(AIRequest request);

    public abstract AIResponse convertToTarget(AIResponse response);

    public abstract AIResponse convertToMaster(AIResponse response);

    protected abstract Stream<String> establishHttpClient(AIRequest request) throws Exception;
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
        this.apiKey = option.getApiKey();
        this.masterReqCls = option.getMasterReqCls();
        this.targetReqCls = option.getTargetReqCls();
        this.masterRespCls = option.getMasterRespCls();
        this.targetRespCls = option.getTargetRespCls();
        this.httpClient = option.getHttpClient();
        checkCls(masterReqCls, targetReqCls, masterRespCls, targetRespCls);
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
