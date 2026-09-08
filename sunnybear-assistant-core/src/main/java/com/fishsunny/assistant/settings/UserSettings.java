package com.fishsunny.assistant.settings;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 04:47
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSettings {

    private String username = "用户";
    public UserSettings setUsername(String username) {
        this.username = username == null ? "用户" : username;
        return this;
    }
    public String getUsername() {
        return username == null ? "用户" : username;
    }

    private String avatar = "";
    public UserSettings setAvatar(String avatar) {
        this.avatar = avatar == null ? "" : avatar;
        return this;
    }
    public String getAvatar() {
        return avatar == null ? "" : avatar;

    }

    private String background = "";
    public UserSettings setBackground(String background) {
        this.background = background == null ? "" : background;
        return this;
    }
    public String getBackground() {
        return background == null ? "" : background;
    }

    private Double opacity;
    public UserSettings setOpacity(Double opacity) {
        this.opacity = opacity == null ? 0.3 : opacity;
        return this;
    }
    public Double getOpacity() {
        return opacity == null ? 0.3 : opacity;
    }

    private String mainColor;
    public UserSettings setMainColor(String mainColor) {
        this.mainColor = mainColor == null ? "lightsalmon" : mainColor;
        return this;
    }
    public String getMainColor() {
        return mainColor == null ? "lightsalmon" : mainColor;
    }

    private Boolean enableAutoSwitchModel = false;
    public UserSettings setEnableAutoSwitchModel(Boolean enableAutoSwitchModel) {
        this.enableAutoSwitchModel = Boolean.TRUE.equals(enableAutoSwitchModel);
        return this;
    }
    public Boolean getEnableAutoSwitchModel() {
        return Boolean.TRUE.equals(enableAutoSwitchModel);
    }
}
