package com.fishsunny.assistant.engine.adapter;

import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
public class AIAdapterRegister {

    private String baseUrl;

    /** 模型列表端点（可选）：配置后前端可据此拉取可选模型做下拉框，留空则只能手填 */
    private String modelUrl;

    private String apiKey;

    /** 自定义请求头，建立连接时随请求带上；Content-Type/Authorization 等基础头由适配器自行添加 */
    private Map<String, String> headers;

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
