package com.fishsunny.assistant.plug.vn.entity;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:16
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class VisualNovelData {

    private String title;

    private String desc;

    // 小说大纲，帮助AI快速理解小说流程，不显示给玩家
    private String outline;

    private String coverImg;

    // 第一个章节的nodeId
    private String startNodeId;
}
