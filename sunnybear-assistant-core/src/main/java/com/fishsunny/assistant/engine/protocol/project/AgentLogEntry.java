package com.fishsunny.assistant.engine.protocol.project;

/*
 * @Usage 子 Agent 中间日志条目 — 用于推送 ###AGENT_LOG### 信号到前端侧边框展示
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/24
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentLogEntry {

    public static final String PHASE_ITERATION = "iteration";
    public static final String PHASE_TOOL_CALL = "tool_call";
    public static final String PHASE_TOOL_RESULT = "tool_result";
    public static final String PHASE_DONE = "done";

    /** 内容最大长度，超出部分截断 */
    private static final int MAX_CONTENT_LENGTH = 500;

    /** 关联的会话 ID */
    private String sessionId;

    /** 日志唯一 ID */
    private String id;

    /** 阶段：iteration | tool_call | tool_result | done */
    private String phase;

    /** 子 Agent 名称，如 "TaskRun" */
    private String agentName;

    /** 简短标题，如 "第2轮: 调用 web_search" */
    private String title;

    /** 详细信息（工具参数等） */
    private String content;

    /** 日志级别：info | warn | error */
    private String level;

    /** 当前迭代轮次（从 1 开始） */
    private int iteration;

    /** 时间戳 */
    private long timestamp;

    public AgentLogEntry() {
        this.timestamp = System.currentTimeMillis();
        this.level = "info";
    }

    /**
     * 截断过长内容，超出长度追加 "…[已截断]"。
     */
    public static String truncate(String content) {
        if (content == null) return null;
        if (content.length() <= MAX_CONTENT_LENGTH) return content;
        return content.substring(0, MAX_CONTENT_LENGTH) + "…[已截断]";
    }
}
