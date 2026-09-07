package com.fishsunny.assistant.engine.adapter;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 08:07
 */

import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.experimental.Accessors;

import java.net.http.HttpClient;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AIAdapterOption {

    protected String baseUrl;

    protected String apiKey;

    /** 自定义请求头，建立连接时随请求带上；Content-Type/Authorization 等基础头由适配器自行添加 */
    protected Map<String, String> headers;

    protected Class<? extends AIRequest> masterReqCls;

    protected Class<? extends AIRequest> targetReqCls;

    protected Class<? extends AIResponse> masterRespCls;

    protected Class<? extends AIResponse> targetRespCls;

    /** 共享 HttpClient，复用连接，避免每次 newHttpClient() */
    protected HttpClient httpClient;

    public AIAdapterOption() {
    }

    public AIAdapterOption(String baseUrl, String apiKey,
                           Class<? extends AIRequest> masterReqCls,
                           Class<? extends AIRequest> targetReqCls,
                           Class<? extends AIResponse> masterRespCls,
                           Class<? extends AIResponse> targetRespCls) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.masterReqCls = masterReqCls;
        this.targetReqCls = targetReqCls;
        this.masterRespCls = masterRespCls;
        this.targetRespCls = targetRespCls;
    }
}
