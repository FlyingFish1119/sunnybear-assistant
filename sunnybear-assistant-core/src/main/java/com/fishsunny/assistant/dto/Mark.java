package com.fishsunny.assistant.dto;

/*
 * @Usage 步骤清单条目 DTO —— mark_upsert_tool 下发给前端持续跟踪的单个步骤。
 *        每次工具调用都会把当前会话的完整 Mark 数组推给前端，前端据此渲染进度。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/14
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Mark {

    /** 待处理 */
    public static final String STATUS_PENDING = "pending";
    /** 正在处理（当前聚焦） */
    public static final String STATUS_IN_PROGRESS = "in_progress";
    /** 已完成 */
    public static final String STATUS_COMPLETED = "completed";
    /** 已取消 */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 步骤稳定标识：同一条步骤跨多次调用保持同一个 id */
    private String id;

    /** 步骤内容 */
    private String content;

    /** 状态：pending | in_progress | completed | cancelled */
    private String status;

    public static boolean isValidStatus(String status) {
        return STATUS_PENDING.equals(status)
                || STATUS_IN_PROGRESS.equals(status)
                || STATUS_COMPLETED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }
}
