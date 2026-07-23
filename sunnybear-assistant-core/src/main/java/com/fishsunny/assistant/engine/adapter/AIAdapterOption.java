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

@Data
@Accessors(chain = true)
public class AIAdapterOption {

    protected String baseUrl;

    protected String apiKey;

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
