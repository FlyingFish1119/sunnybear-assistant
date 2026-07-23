package com.fishsunny.assistant.engine.protocol.standard.chat.message;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 06:37
 */

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardSystemMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardToolMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardUserMessage;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        defaultImpl = StandardAssistantMessage.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = StandardUserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = StandardSystemMessage.class, name = "system"),
        @JsonSubTypes.Type(value = StandardAssistantMessage.class, name = "assistant"),
        @JsonSubTypes.Type(value = StandardToolMessage.class, name = "tool"),
})
public interface StandardMessage {
}
