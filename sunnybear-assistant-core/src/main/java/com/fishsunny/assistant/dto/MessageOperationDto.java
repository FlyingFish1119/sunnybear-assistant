package com.fishsunny.assistant.dto;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/29 15:37
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MessageOperationDto {

    private String mode;

    private String messageId;

    public MessageOperationDto() {
    }

    public MessageOperationDto(String mode, String messageId) {
        this.mode = mode;
        this.messageId = messageId;
    }
}
