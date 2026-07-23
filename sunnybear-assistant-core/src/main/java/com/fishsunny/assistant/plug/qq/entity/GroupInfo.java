package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ 群信息
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
public class GroupInfo {

    /** 群号 */
    @JsonProperty("group_id")
    private Long groupId;

    /** 群名称 */
    @JsonProperty("group_name")
    private String groupName;

    /** 成员数 */
    @JsonProperty("member_count")
    private Integer memberCount;

    /** 最大成员数 */
    @JsonProperty("max_member_count")
    private Integer maxMemberCount;
}
