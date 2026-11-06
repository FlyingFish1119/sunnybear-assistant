package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 自动记忆整理待办，对应 session_memory_job 表
 *
 *        一行 = 一段等待整理的对话：每轮对话把到期时间往后推，静默到点后跑一次整理，跑完把这行删掉。
 *        不保留历史、不记结果 —— 这是待办不是账本，执行过程写日志。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class SessionMemoryJob {

    private String sessionId;

    /** 到期时刻：最后一条用户消息时间 + 空闲小时数 */
    private LocalDateTime dueTime;

    /** 本段对话的计时依据（最后一条用户消息时间），兼作"这一轮待办"的版本号 */
    private LocalDateTime lastMessageTime;
}
