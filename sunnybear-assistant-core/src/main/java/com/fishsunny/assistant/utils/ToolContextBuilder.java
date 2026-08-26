package com.fishsunny.assistant.utils;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/6 20:59
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolContextBuilder {

    public static Map<String, Object> defaultBuild(Object... args) {
        List<String> clsSimpleNames = new ArrayList<>();
        for (Object arg : args) {
            String simpleName = arg.getClass().getSimpleName();
            String prefix = simpleName.substring(0, 1).toLowerCase();
            simpleName = prefix + simpleName.substring(1);
            if (clsSimpleNames.contains(simpleName)) {
                throw new RuntimeException("参数类名重复：" + simpleName);
            }
            clsSimpleNames.add(simpleName);
        }
        Map<String, Object> context = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            context.put(clsSimpleNames.get(i), args[i]);
        }
        return context;
    }

    public static Map<String, Object> minimumBuild(WebSocketSession session, ChatSession chatSession) {
        HashMap<String, Object> context = new HashMap<>();
        context.put("session", session);
        context.put("chatSession", chatSession);
        return context;
    }

    /**
     * 判断当前会话是否为"无审查模式"（unreviewed=true）。
     * <p>
     * 无审查模式下，工具应跳过用户确认与 AI 危险审查（包括命令黑名单硬拦截），直接执行。
     * 注意：这里的 unreviewed 与工具内 AUTO 模式常量（"危险操作需确认"）语义相反。
     *
     * @param context 工具上下文（须含 chatSession）
     * @return true 表示当前会话处于无审查模式
     */
    public static boolean isUnreviewed(Map<String, Object> context) {
        Object cs = context.get("chatSession");
        return cs instanceof ChatSession s && Boolean.TRUE.equals(s.getUnreviewed());
    }
}
