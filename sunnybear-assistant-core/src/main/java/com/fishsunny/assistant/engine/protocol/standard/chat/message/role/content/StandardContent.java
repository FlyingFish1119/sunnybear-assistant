package com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.audio.StandardAudioContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.image.StandardImageContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.text.StandardTextContent;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = StandardTextContent.class, name = "text"),
        @JsonSubTypes.Type(value = StandardImageContent.class, name = "image_url"),
        @JsonSubTypes.Type(value = StandardAudioContent.class, name = "input_audio")
})
public interface StandardContent {
}
