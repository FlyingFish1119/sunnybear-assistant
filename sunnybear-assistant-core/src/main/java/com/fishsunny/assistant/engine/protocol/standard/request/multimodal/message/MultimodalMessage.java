package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message;

/*
 * @Usage 多模态 tool 结果协议的请求消息基类（OpenAI 兼容格式）。
 *        与 Standard 协议的主要差异在 tool 消息：content 支持多模态 content 数组。
 *        本协议与 Standard 完全解耦，不影响 Standard 链路。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalSystemMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalToolMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalUserMessage;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        defaultImpl = MultimodalAssistantMessage.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = MultimodalUserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = MultimodalSystemMessage.class, name = "system"),
        @JsonSubTypes.Type(value = MultimodalAssistantMessage.class, name = "assistant"),
        @JsonSubTypes.Type(value = MultimodalToolMessage.class, name = "tool"),
})
public abstract class MultimodalMessage {
}
