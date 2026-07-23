package com.fishsunny.assistant.plug.character.entity;

/*
 * @Usage 角色-会话映射实体
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CharacterSessionMapping {

    private String id;

    /** 会话 ID */
    private String sessionId;

    /** 角色 ID */
    private String characterId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public CharacterSessionMapping() {
    }

    public CharacterSessionMapping(String sessionId, String characterId) {
        this.sessionId = sessionId;
        this.characterId = characterId;
    }
}
