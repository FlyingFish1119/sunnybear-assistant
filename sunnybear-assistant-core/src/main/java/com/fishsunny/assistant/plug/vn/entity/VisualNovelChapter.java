package com.fishsunny.assistant.plug.vn.entity;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 11:04
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class VisualNovelChapter {

    private Integer order;

    private String title;

    // 章节核心内容用于给AI快速理解章节大意，不显示给玩家
    private String content;

    public String buildChapterId() {
        return order + "-" + title;
    }
}
