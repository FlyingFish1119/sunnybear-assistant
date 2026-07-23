package com.fishsunny.assistant.engine.adapter;

import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AIAdapterRegister {

    private String baseUrl;

    private String apiKey;

    private Class<? extends AIAdapter> adapterCls;

    private Class<? extends AIRequest> masterReqCls;

    private Class<? extends AIRequest> targetReqCls;

    private Class<? extends AIResponse> targetRespCls;

    private Class<? extends AIResponse> masterRespCls;

    private String apiName;

    private Boolean stream;

    public AIAdapterRegister() {
    }
}
