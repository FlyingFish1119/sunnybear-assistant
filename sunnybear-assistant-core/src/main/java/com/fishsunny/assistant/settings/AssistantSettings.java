package com.fishsunny.assistant.settings;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 16:25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantSettings {

    private String assistantName;
    public String getAssistantName() {
        return assistantName == null ? "Assistant" : assistantName;
    }
    public AssistantSettings setAssistantName(String assistantName) {
        this.assistantName = assistantName == null ? "Assistant" : assistantName;
        return this;
    }

    private String avatar;
    public String getAvatar() {
        return avatar == null ? "" : avatar;
    }
    public AssistantSettings setAvatar(String avatar) {
        this.avatar = avatar == null ? "" : avatar;
        return this;
    }
}
