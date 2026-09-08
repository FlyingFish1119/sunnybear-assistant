package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 对话状态交互控制器 —— 处理前端在对话过程中的确认、中止等请求
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/10
 */

import com.fishsunny.assistant.dto.ToolConfirm;
import com.fishsunny.assistant.dto.ToolQuestionAnswer;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 等待中的工具结构化提问结果 */
    private static final Map<String, CompletableFuture<ToolQuestionAnswer>> pendingQuestions = new ConcurrentHashMap<>();

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
     * 前端回传工具结构化提问结果
     */
    @PostMapping("/question")
    public Map<String, Object> answerQuestion(@RequestBody ToolQuestionAnswer toolQuestionAnswer) {
        CompletableFuture<ToolQuestionAnswer> future = pendingQuestions.remove(toolQuestionAnswer.getId());
        if (future != null) {
            future.complete(toolQuestionAnswer);
            log.debug("工具结构化提问已处理: id={}, cancelled={}", toolQuestionAnswer.getId(), toolQuestionAnswer.getCancelled());
            return Map.of("success", true);
        }
        log.warn("收到无效的提问 ID（已超时或不存在）: {}", toolQuestionAnswer.getId());
        return Map.of("success", false, "message", "提问 ID 无效或已过期");
    }

    /**
     * 前端中止流式传输
     */
    @PostMapping("/stop")
    public Map<String, Object> stopStreaming(@RequestParam(value = "sessionId", required = false) String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            ChatHttpHandler.getPASS_SIGN().remove(sessionId);
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

    // ======================== 工具侧静态方法（结构化提问） ========================

    /**
     * 工具调用：注册一个待作答的提问请求并阻塞等待用户回答。
     *
     * @param uuid            提问请求的唯一标识
     * @param timeoutSeconds  超时时间（秒）；为空或 <=0 表示不超时，一直等待用户作答
     * @return 用户的作答结果（含取消标记）；超时/被中断返回 null
     */
    public static ToolQuestionAnswer awaitQuestion(String uuid, Integer timeoutSeconds) {
        CompletableFuture<ToolQuestionAnswer> future = new CompletableFuture<>();
        pendingQuestions.put(uuid, future);
        try {
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                return future.get();
            }
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("工具结构化提问等待超时或被中断: uuid={}", uuid);
            return null;
        }
    }

    /**
     * 工具调用：清理指定提问请求（在 finally 块中调用）。
     */
    public static void cleanupQuestion(String uuid) {
        CompletableFuture<ToolQuestionAnswer> future = pendingQuestions.remove(uuid);
        if (future != null && !future.isDone()) {
            // 未作答即中断 → 按"用户未作答"补齐空结果，避免工具永久阻塞
            ToolQuestionAnswer cancelled = new ToolQuestionAnswer().setId(uuid).setCancelled(true);
            future.complete(cancelled);
        }
    }

    // ======================== 只读查询（供总线重放等场景判断信号是否仍待用户应答） ========================

    /** 该确认请求是否仍挂在服务端等待用户确认（未被应答 / 未超时 / 未清理） */
    public static boolean isConfirmPending(String uuid) {
        return uuid != null && pendingConfirmations.containsKey(uuid);
    }

    /** 该结构化提问是否仍挂在服务端等待用户作答（未被作答 / 未超时 / 未清理） */
    public static boolean isQuestionPending(String uuid) {
        return uuid != null && pendingQuestions.containsKey(uuid);
    }
}
