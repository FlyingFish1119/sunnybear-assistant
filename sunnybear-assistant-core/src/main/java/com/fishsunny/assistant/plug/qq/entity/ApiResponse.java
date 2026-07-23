package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage OneBot API 通用响应
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:45
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ApiResponse {

    /** 状态: ok / failed */
    private String status;

    /** 返回码 */
    private Integer retcode;

    /** 错误信息 */
    private String msg;

    /** 数据体 */
    private Object data;

    /** 消息 ID（发送消息接口返回） */
    @JsonProperty("message_id")
    private Long messageId;
}
