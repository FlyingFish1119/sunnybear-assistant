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

    private String avatar = "";

    private String background = "";

    private Double opacity = 0.3;

    private String mainColor = "lightsalmon";

    private Boolean enableAutoSwitchModel = false;
}
