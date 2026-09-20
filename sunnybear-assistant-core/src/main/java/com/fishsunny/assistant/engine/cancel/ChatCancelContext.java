package com.fishsunny.assistant.engine.cancel;

/*
 * @Usage 取消令牌的线程上下文传播 —— 让子请求自动继承父令牌。
 *
 * 对话链路里工具内部会再次调用 ChatHttpHandler.translate（OCR、网页总结、子 Agent 等），
 * 这些子请求若不继承父令牌，「停止」就管不到它们。靠本类做隐式传递，工具代码无需改动：
 * 收流线程绑定令牌 → ToolExecutor 提交工具任务时把它绑到工具线程 → 工具内部的 translate 直接复用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/20
 */
public final class ChatCancelContext {

    private static final ThreadLocal<ChatCancelToken> CURRENT = new ThreadLocal<>();

    private ChatCancelContext() {
    }

    /** 当前线程绑定的令牌；返回 null 表示这是顶层调用（需要自己去注册表开启一轮） */
    public static ChatCancelToken current() {
        return CURRENT.get();
    }

    public static void bind(ChatCancelToken token) {
        CURRENT.set(token);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /** 当前线程是否处于已取消的轮次中。未绑定视为未取消（如工具循环最外层尚未建连时） */
    public static boolean isCancelled() {
        ChatCancelToken token = CURRENT.get();
        return token != null && token.isCancelled();
    }
}
