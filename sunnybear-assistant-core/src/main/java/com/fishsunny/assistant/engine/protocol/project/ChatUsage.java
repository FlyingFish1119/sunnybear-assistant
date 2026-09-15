package com.fishsunny.assistant.engine.protocol.project;

/**
 * 对话 token 用量在 extension 字段中的键约定。
 *
 * <ul>
 *   <li>assistant 消息：{@code ChatMessage.extension["chat_usage"]} = 本轮各项 token 的 map；</li>
 *   <li>会话累计：{@code ChatSession.extension["chat_total_tokens"]} 等 chat_ 前缀的累计值。</li>
 * </ul>
 *
 * 写入时一律「合并」到已有 extension，不得整体覆盖（避免抹掉插件写入的 key）。
 */
public final class ChatUsage {

    private ChatUsage() {
    }

    /** 消息 extension 中存放本轮用量的键 */
    public static final String MESSAGE_KEY = "chat_usage";

    /** 会话 extension：累计输入 token */
    public static final String SESSION_PROMPT_TOKENS = "chat_prompt_tokens";

    /** 会话 extension：累计输出 token */
    public static final String SESSION_COMPLETION_TOKENS = "chat_completion_tokens";

    /** 会话 extension：累计总 token */
    public static final String SESSION_TOTAL_TOKENS = "chat_total_tokens";

    /** 会话 extension：累计命中缓存输入 token */
    public static final String SESSION_CACHED_TOKENS = "chat_cached_tokens";

    /** 会话 extension：累计思考 token */
    public static final String SESSION_REASONING_TOKENS = "chat_reasoning_tokens";

    /** 会话 extension：累计 AI 调用轮次（含工具子轮） */
    public static final String SESSION_ROUNDS = "chat_rounds";
}
