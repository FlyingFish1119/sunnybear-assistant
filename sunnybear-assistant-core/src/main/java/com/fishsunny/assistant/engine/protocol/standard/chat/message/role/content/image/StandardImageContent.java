package com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.StandardContent;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardImageContent implements StandardContent {

    private final String type = "image_url";

    private StandardImageUrl image_url;

    public StandardImageContent(String url) {
        StandardImageUrl standardImageUrl = new StandardImageUrl();
        standardImageUrl.setUrl(url);
        this.image_url = standardImageUrl;
    }

    public StandardImageContent() {
    }
}
