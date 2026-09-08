package com.fishsunny.assistant.dto;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 04:45
 */

import com.fishsunny.assistant.exception.UserException;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

@Data
@Accessors(chain = true)
public class AssistantMessageEditDTO {

    private String id;
    public void setId(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("消息的唯一标识不能为空");
        }
        this.id = id;
    }

    private String content;
    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public AssistantMessageEditDTO() {
    }
}
