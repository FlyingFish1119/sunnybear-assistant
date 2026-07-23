package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage OneBot 发送消息请求体
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:45
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SendMsgRequest {

    /** 对方 QQ 号（私聊）或群号（群聊） */
    @JsonProperty("user_id")
    private Long userId;

    /** 群号（发送群消息时使用） */
    @JsonProperty("group_id")
    private Long groupId;

    /** 消息内容：字符串或消息段数组 */
    private Object message;

    /** 是否自动转义 CQ 码 */
    @JsonProperty("auto_escape")
    private Boolean autoEscape;
}
