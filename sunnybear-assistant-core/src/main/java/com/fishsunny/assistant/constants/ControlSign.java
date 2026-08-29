package com.fishsunny.assistant.constants;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30 01:38
 */

public class ControlSign {

    // 应携带一个 sessionId 字符串
    public static final String SIGN_START = "###START###";
    // 应携带一个 sessionId 字符串
    public static final String SIGN_END = "###END###";
    // 应携带一个 替换前的 messageId 字符串
    public static final String SIGN_REPLACE = "###REPLACE###";
    // 应携带一个 ToolAsk json 对象
    public static final String SIGN_TOOL_ASK = "###TOOL_ASK###";
    // 应携带一个 session json 对象
    public static final String UPDATE_SESSION = "###UPDATE_SESSION###";
    // 应携带一个 AgentLogEntry json 对象
    public static final String SIGN_AGENT_LOG = "###AGENT_LOG###";
    // 应携带一个 sessionId 字符串（知识库内容被自动注入到对话提示词时通知前端）
    public static final String SIGN_KNOWLEDGE_HIT = "###KNOWLEDGE_HIT###";
    // 应携带一个 sessionId 字符串
    public static final String SIGN_TOOL_CALL_FINISH = "###TOOL_CALL_FINISH###";
    // 应携带一个 sessionId 字符串
    public static final String SIGN_REPLAY_MESSAGE = "###REPLAY_MESSAGE###";

    // 前端需要重播消息
    public static final String SIGN_REQUIRE_REPLAY_MESSAGE = "###REQUIRE_REPLAY_MESSAGE###";
}
