package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ消息实体（OneBot 标准）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:30
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class QQMessage {

    /** 收到消息的机器人 QQ 号 */
    @JsonProperty("self_id")
    private Long selfId;

    /** 发送者 QQ 号 */
    @JsonProperty("user_id")
    private Long userId;

    /** 事件发生的时间戳 */
    private Long time;

    /** 消息 ID */
    @JsonProperty("message_id")
    private Long messageId;

    /** 消息序号 */
    @JsonProperty("message_seq")
    private Long messageSeq;

    /** 真实消息 ID */
    @JsonProperty("real_id")
    private Long realId;

    /** 真实消息序号 */
    @JsonProperty("real_seq")
    private String realSeq;

    /** 消息类型: private / group */
    @JsonProperty("message_type")
    private String messageType;

    /** 发送者信息 */
    private QQMessageSender sender;

    /** 消息内容（CQ 码格式） */
    @JsonProperty("raw_message")
    private String rawMessage;

    /** 字体 */
    private Integer font;

    /** 消息子类型: friend / group / normal 等 */
    @JsonProperty("sub_type")
    private String subType;

    /** 消息段数组 */
    private List<QQMessageSegment> message;

    /** 消息格式: array / string */
    @JsonProperty("message_format")
    private String messageFormat;

    /** 上报类型: message / notice / request */
    @JsonProperty("post_type")
    private String postType;

    /** 群号（群聊消息时有值） */
    @JsonProperty("group_id")
    private Long groupId;

    /** 目标 ID（私聊为对方 QQ 号，群聊为群号） */
    @JsonProperty("target_id")
    private Long targetId;
}
