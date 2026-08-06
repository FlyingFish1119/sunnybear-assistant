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
}
