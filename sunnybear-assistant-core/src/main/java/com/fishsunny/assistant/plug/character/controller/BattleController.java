package com.fishsunny.assistant.plug.character.controller;

/*
 * @Usage 战斗回合交互控制器 —— 处理前端在战斗过程中的回合行动提交。
 *        战斗工具（FightEngineTool）通过 WebSocket 推送回合数据后，在此阻塞等待玩家响应。
 *        超时设为 10 分钟，唯一合理的超时场景是用户关闭了页面。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.character.dto.BattleTurnAction;
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
@RequestMapping("/character/battle")
public class BattleController {

    private static final int TURN_TIMEOUT_MINUTES = 10;

    private static final Map<String, CompletableFuture<BattleTurnAction>> pendingActions = new ConcurrentHashMap<>();

    @PostMapping("/action")
    public RestResponse submitAction(@RequestBody BattleTurnAction action) {
        if (action == null || action.getSessionId() == null) {
            log.warn("收到无效的战斗行动请求");
            return new RestResponse().error("sessionId 不能为空");
        }

        CompletableFuture<BattleTurnAction> future = pendingActions.remove(action.getSessionId());
        if (future != null) {
            future.complete(action);
            log.debug("战斗行动已处理: sessionId={}, skillIndex={}", action.getSessionId(), action.getSkillIndex());
            return new RestResponse().success(true);
        }

        log.warn("收到无效的战斗行动（已超时或不存在）: sessionId={}", action.getSessionId());
        return new RestResponse().error("战斗行动 ID 无效或已过期");
    }

    // ======================== 工具侧静态方法 ========================

    /**
     * 工具调用：注册一个等待中的战斗行动并阻塞等待前端提交。
     *
     * @param sessionId 会话 ID，用于关联 WebSocket 连接
     * @return 玩家行动，若超时则返回 null
     */
    public static BattleTurnAction awaitBattleAction(String sessionId) {
        CompletableFuture<BattleTurnAction> future = new CompletableFuture<>();
        try {
            pendingActions.put(sessionId, future);
            return future.get(TURN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("战斗行动等待超时或被中断: sessionId={}", sessionId);
            return null;
        }
    }

    /**
     * 工具调用：清理指定会话的等待请求（在 finally 块中调用）。
     */
    public static void cleanupAction(String sessionId) {
        CompletableFuture<BattleTurnAction> future = pendingActions.remove(sessionId);
        if (future != null && !future.isDone()) {
            future.complete(null);
        }
    }
}
