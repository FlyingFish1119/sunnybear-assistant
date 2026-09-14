package com.fishsunny.assistant.dto;

/*
 * @Usage 步骤清单推送负载 —— ###MARK### 信号携带的对象：会话 id + 完整步骤数组。
 *        前端按 sessionId 过滤，只跟踪当前会话的清单。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/14
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class MarkPayload {

    /** 关联的会话 id */
    private String sessionId;

    /** 当前会话的完整步骤数组（全量，前端整体替换） */
    private List<Mark> marks = new ArrayList<>();
}
