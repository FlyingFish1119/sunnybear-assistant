package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage session-知识库映射实体 —— 记录某个会话已注入的知识条目 ID 列表
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class SessionKnowledgeRecord {

    private String id;
    private String sessionId;
    /** 已注入的知识条目 ID 列表（JSON 数组字符串，如 ["id1","id2"]） */
    private String knowledgeIds;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
