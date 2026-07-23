package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ 好友信息
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
public class FriendInfo {

    /** 好友 QQ 号 */
    @JsonProperty("user_id")
    private Long userId;

    /** 好友昵称 */
    private String nickname;

    /** 备注名 */
    private String remark;
}
