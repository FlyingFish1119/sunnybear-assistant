package com.fishsunny.assistant.plug.vn.entity;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:17
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Accessors(chain = true)
public class VisualNovelSave {

    private String currentChapter;

    private String currentNodeId;

    private Map<String, String> eventMap = new ConcurrentHashMap<>();
}
