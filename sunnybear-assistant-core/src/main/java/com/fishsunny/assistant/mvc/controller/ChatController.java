package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 对话状态交互控制器 —— 处理前端在对话过程中的确认、中止等请求
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/10
 */

import com.fishsunny.assistant.dto.ToolConfirm;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    /** 等待中的工具确认请求 */
    private static final Map<String, CompletableFuture<Boolean>> pendingConfirmations = new ConcurrentHashMap<>();

    /**
     * 前端回传工具确认结果
     */
    @PostMapping("/confirm")
    public Map<String, Object> confirmTool(@RequestBody ToolConfirm toolConfirm) {
        CompletableFuture<Boolean> future = pendingConfirmations.remove(toolConfirm.getId());
        if (future != null) {
            future.complete(toolConfirm.isConfirm());
            log.debug("工具确认已处理: id={}, confirm={}", toolConfirm.getId(), toolConfirm.isConfirm());
            return Map.of("success", true);
        }
        log.warn("收到无效的确认 ID（已超时或不存在）: {}", toolConfirm.getId());
        return Map.of("success", false, "message", "确认 ID 无效或已过期");
    }

    /**
     * 前端中止流式传输
     */
    @PostMapping("/stop")
    public Map<String, Object> stopStreaming(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId != null && !sessionId.isEmpty()) {
            ChatHttpHandler.getSTOP_SIGN().add(sessionId);
            log.info("收到中止信号，已移除 sessionId: {}", sessionId);
            return Map.of("success", true);
        }
        return Map.of("success", false, "message", "sessionId 不能为空");
    }

    // ======================== 工具侧静态方法 ========================

    /**
     * 工具调用：注册一个待确认的请求并阻塞等待用户确认。
     *
     * @param uuid            确认请求的唯一标识
     * @param timeoutSeconds  超时时间（秒）；为空或 <=0 表示不超时，一直等待用户确认
     * @return TRUE=用户确认，FALSE=用户拒绝，null=超时/被中断
     */
    public static Boolean awaitConfirm(String uuid, Integer timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConfirmations.put(uuid, future);
        try {
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                return future.get();
            }
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("工具确认等待超时或被中断: uuid={}", uuid);
            return null;
        }
    }

    /**
     * 工具调用：清理指定确认请求（在 finally 块中调用）。
     */
    public static void cleanupConfirm(String uuid) {
        CompletableFuture<Boolean> future = pendingConfirmations.remove(uuid);
        if (future != null && !future.isDone()) {
            future.complete(false);
        }
    }
}
