package com.fishsunny.assistant.engine.adapter.standard.proxy;

/*
 * @Usage proxy 调度器（流式）：基于 Standard 的纯文字聊天通道。
 *
 *        有些第三方中转/代理端点不认 tools 字段，带了直接报参数错误；
 *        这里在父类正常转换之后把工具数组整个摘掉，请求体里不会出现 "tools"。
 *        纯文字聊天，不参与任何工具调用。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.adapter.standard.old.StandardStreamAIAdapter;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.request.old.StandardAIRequest;

public class ProxyStandardStreamAIAdapter extends StandardStreamAIAdapter {

    public ProxyStandardStreamAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        StandardAIRequest target = (StandardAIRequest) super.convertToTarget(request);
        // 纯文字聊天：tools 传 null，@JsonInclude(NON_NULL) 会让这个键整个消失
        return target.setTools(null);
    }
}
