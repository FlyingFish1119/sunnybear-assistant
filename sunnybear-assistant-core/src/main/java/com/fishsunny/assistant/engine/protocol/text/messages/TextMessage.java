package com.fishsunny.assistant.engine.protocol.text.messages;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 06:37
 */

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextAssistantMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextSystemMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextToolMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextUserMessage;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        defaultImpl = TextAssistantMessage.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextUserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = TextSystemMessage.class, name = "system"),
        @JsonSubTypes.Type(value = TextAssistantMessage.class, name = "assistant"),
        @JsonSubTypes.Type(value = TextToolMessage.class, name = "tool"),
})
public interface TextMessage {
}
