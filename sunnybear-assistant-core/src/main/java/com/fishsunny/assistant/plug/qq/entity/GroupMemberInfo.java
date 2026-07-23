package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ 群成员信息
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
public class GroupMemberInfo {

    /** 群号 */
    @JsonProperty("group_id")
    private Long groupId;

    /** 成员 QQ 号 */
    @JsonProperty("user_id")
    private Long userId;

    /** 昵称 */
    private String nickname;

    /** 群名片 / 群昵称 */
    private String card;

    /** 性别: male / female / unknown */
    private String sex;

    /** 年龄 */
    private Integer age;

    /** 地区 */
    private String area;

    /** 加群时间戳 */
    @JsonProperty("join_time")
    private Long joinTime;

    /** 最后发言时间戳 */
    @JsonProperty("last_sent_time")
    private Long lastSentTime;

    /** 成员等级 */
    private String level;

    /** 角色: owner / admin / member */
    private String role;

    /** 是否不良记录成员 */
    private Boolean unfriendly;

    /** 专属头衔 */
    @JsonProperty("title")
    private String title;

    /** 专属头衔过期时间戳 */
    @JsonProperty("title_expire_time")
    private Long titleExpireTime;

    /** 是否允许修改群名片 */
    @JsonProperty("card_changeable")
    private Boolean cardChangeable;
}
