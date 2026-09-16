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
    // 应携带一个 ToolQuestion json 对象（结构化提问：分组 + 每题候选）
    public static final String SIGN_TOOL_QUESTION = "###TOOL_QUESTION###";
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

    // 应携带一个 {sessionId, audio} json 对象：TTS 逐句音频帧（mp3 base64）。
    // 不参与断线重放（shouldReplay 排除），避免旧语音注入新轮次
    public static final String SIGN_TTS_AUDIO = "###TTS_AUDIO###";

    // 应携带一个 MarkPayload json 对象（{sessionId, marks:[...]}）：步骤清单全量数组，前端持续跟踪
    public static final String SIGN_MARK = "###MARK###";

    // 前端需要重播消息
    public static final String SIGN_REQUIRE_REPLAY_MESSAGE = "###REQUIRE_REPLAY_MESSAGE###";

    // 应携带一个 sessionId 字符串：上下文超限，开始压缩（正在调用模型总结旧对话）。
    // 前端收到后展示「压缩中」状态，避免在 START 之后长时间空等。实时信号，不参与断线重放
    public static final String SIGN_CONTEXT_COMPRESSING = "###CONTEXT_COMPRESSING###";

    // 应携带一个 sessionId 字符串：上下文超限完成压缩（旧消息已删、摘要已并入 root 用户消息），
    // 前端收到后重拉最新消息即可。实时信号，不参与断线重放
    public static final String SIGN_CONTEXT_COMPRESSED = "###CONTEXT_COMPRESSED###";

    // 应携带一个 sessionId 字符串：本次压缩结束但未改变消息（总结失败/结果为空等），
    // 前端收到后关闭「压缩中」状态即可，无需重拉。实时信号，不参与断线重放
    public static final String SIGN_CONTEXT_COMPRESS_END = "###CONTEXT_COMPRESS_END###";
}
