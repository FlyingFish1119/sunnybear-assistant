package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ 机器人登录信息
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 16:00
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LoginInfo {

    /** 机器人 QQ 号 */
    @JsonProperty("user_id")
    private Long userId;

    /** 机器人昵称 */
    private String nickname;
}
