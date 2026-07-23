package com.fishsunny.assistant.plug.qq.entity;

/*
 * @Usage QQ消息段（消息数组中的单个元素）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:30
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
public class QQMessageSegment {

    /** 消息段类型: text / image / at / face 等 */
    private String type;

    /** 消息段数据 */
    private Map<String, String> data;
}
