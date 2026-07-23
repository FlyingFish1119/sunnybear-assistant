package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ消息发送者信息
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:30
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class QQMessageSender {

    /** 发送者 QQ 号 */
    @JsonProperty("user_id")
    private Long userId;

    /** 发送者昵称 */
    private String nickname;

    /** 群名片 / 备注（群聊时有值，私聊为空） */
    private String card;
}
