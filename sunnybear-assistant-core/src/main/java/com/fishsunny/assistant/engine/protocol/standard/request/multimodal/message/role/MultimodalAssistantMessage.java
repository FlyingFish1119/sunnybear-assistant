package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalAssistantMessage extends MultimodalMessage {

    private final String role = "assistant";

    private String content;

    private String reasoning_content;

    /**
     * 默认空列表：响应反序列化时若报文无 tool_calls 字段，保持判空安全。
     * 请求侧显式传 null 时透传不兜底，序列化由 NON_NULL 省略该字段，
     * 避免向只接受"无该字段"的端点发送 {@code "tool_calls": []}。
     */
    private List<StandardToolRequest> tool_calls = new ArrayList<>();

    public MultimodalAssistantMessage() {
    }

    public MultimodalAssistantMessage(String content, String reasoning_content) {
        this.content = content;
        this.reasoning_content = reasoning_content;
    }
}
