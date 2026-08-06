package com.fishsunny.agent.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fishsunny.agent.AgentAccessibilityService;
import com.fishsunny.agent.protocol.CommandRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 命令分发器：接收 WebSocket 下发的命令 → 调用 AccessibilityService 执行 → 回调结果。
 * <p>
 * 所有无障碍操作必须在主线程执行，耗时操作（如 wait_for_text）在后台线程轮询。
 */
public class CommandExecutor {

    private static final String TAG = "SunnyBear-Cmd";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 执行命令，结果通过 callback 返回 */
    public static void execute(WebSocketManager wsManager,
                               CommandRequest cmd,
                               WebSocketManager.ResponseCallback callback) {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            callback.onResult("错误：无障碍服务未启动，请在 设置→无障碍 中开启 SunnyBear Agent");
            return;
        }

        executor.submit(() -> {
            try {
                String result = dispatch(service, cmd);
                callback.onResult(result);
            } catch (Exception e) {
                Log.e(TAG, "命令执行异常: " + cmd.method, e);
                callback.onResult("命令 [" + cmd.method + "] 执行失败: " + e.getMessage());
            }
        });
    }

    private static String dispatch(AgentAccessibilityService service, CommandRequest cmd) throws Exception {
        CommandRequest.Params p = cmd.params != null ? cmd.params : new CommandRequest.Params();

        switch (cmd.method) {
            case "click":
                return service.click(p.x, p.y, p.text);
            case "long_press":
                return service.longPress(p.x, p.y, p.duration != null ? p.duration : 1000);
            case "swipe":
                return service.swipe(p.x1, p.y1, p.x2, p.y2, p.duration);
            case "type":
                return service.typeText(p.inputText, p.targetHint);
            case "get_ui_tree":
                return service.getUiTree(p.maxDepth != null ? p.maxDepth : 10);
            case "screenshot":
                return service.takeScreenshot();
            case "launch_app":
                return service.launchApp(p.packageName);
            case "press_key":
                return service.pressKey(p.key);
            case "scroll":
                return service.scroll(p.direction);
            case "wait_for_text":
                return service.waitForText(p.text, p.timeout != null ? p.timeout : 5000);
            case "get_focused_node":
                return service.getFocusedNodeInfo();
            default:
                return "不支持的命令: " + cmd.method + "。支持的命令: click, long_press, swipe, type, get_ui_tree, screenshot, launch_app, press_key, scroll, wait_for_text, get_focused_node";
        }
    }
}
