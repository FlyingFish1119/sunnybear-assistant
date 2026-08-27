package com.fishsunny.assistant.plug.world.entity;

/*
 * @Usage 世界-会话映射实体（群聊会话绑定到世界观）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class WorldSessionMapping {

    private String id;

    /** 会话 ID */
    private String sessionId;

    /** 世界观 ID */
    private String worldId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public WorldSessionMapping() {
    }

    public WorldSessionMapping(String sessionId, String worldId) {
        this.sessionId = sessionId;
        this.worldId = worldId;
    }
}
