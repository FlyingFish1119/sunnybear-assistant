package com.fishsunny.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务核心：接收远程命令，执行屏幕操作。
 * <p>
 * 所有 UI 操作（点击、滑动、输入、遍历节点树）均通过 AccessibilityService API 完成。
 * 使用 {@code dispatchGesture()} 执行手势，使用 {@code AccessibilityNodeInfo} 遍历和操作 UI 元素。
 * <p>
 * 使用方法：在 设置→无障碍→SunnyBear Agent 中手动开启。
 */
public class AgentAccessibilityService extends AccessibilityService {

    private static final String TAG = "SunnyBear-A11y";
    private static AgentAccessibilityService instance;

    public static AgentAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // 不处理事件，仅做事件透传
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "无障碍服务已销毁");
    }

    // ==================== 公开方法：供 CommandExecutor 调用 ====================

    /**
     * 点击指定坐标或匹配文本的 UI 元素。
     * <ul>
     *   <li>提供 x,y → 直接点击该坐标</li>
     *   <li>提供 text → 查找包含该文本的节点，点击其中心</li>
     *   <li>都提供 → 先尝试坐标，失败后尝试文本</li>
     * </ul>
     */
    public String click(Integer x, Integer y, String text) {
        if (x != null && y != null) {
            performClick(x, y);
            return "已点击坐标 (" + x + ", " + y + ")";
        }
        if (text != null && !text.isEmpty()) {
            AccessibilityNodeInfo node = findNodeByText(text);
            if (node == null) {
                return "未找到包含文本 [" + text + "] 的可点击元素";
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int cx = bounds.centerX();
            int cy = bounds.centerY();
            CharSequence nodeText = node.getText();
            node.recycle();
            performClick(cx, cy);
            return "已点击文本 [" + nodeText + "] 位置 (" + cx + ", " + cy + ")";
        }
        return "click 命令缺少参数：请提供 (x, y) 或 text";
    }

    /** 长按指定坐标 */
    public String longPress(Integer x, Integer y, Integer durationMs) {
        if (x == null || y == null) {
            return "long_press 命令需要 x, y 参数";
        }
        long duration = durationMs != null ? durationMs : 1000;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        android.graphics.Path clickPath = new android.graphics.Path();
        clickPath.moveTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, duration));
        return dispatchGestureAndWait(builder.build())
                ? "已长按 (" + x + ", " + y + ") " + duration + "ms"
                : "长按失败";
    }

    /**
     * 滑动操作。
     */
    public String swipe(Integer x1, Integer y1, Integer x2, Integer y2, Integer durationMs) {
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return "swipe 命令需要 x1, y1, x2, y2 参数";
        }
        long duration = durationMs != null ? durationMs : 300;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGestureAndWait(builder.build())
                ? "已滑动 (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ") " + duration + "ms"
                : "滑动失败";
    }

    /**
     * 在输入框中输入文本。优先使用当前焦点节点，否则按 targetHint 查找目标输入框。
     */
    @SuppressWarnings("deprecation")
    public String typeText(String text, String targetHint) {
        if (text == null || text.isEmpty()) {
            return "type 命令缺少 text 参数";
        }

        AccessibilityNodeInfo target = null;
        // 优先使用当前焦点
        AccessibilityNodeInfo focused = getRootInActiveWindow();
        if (focused != null) {
            target = focused.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        // 如果没焦点，按 hint 查找
        if (target == null && targetHint != null && !targetHint.isEmpty()) {
            target = findNodeByHint(targetHint);
        }
        if (target == null) {
            return "未找到输入框"
                    + (targetHint != null ? "（hint: " + targetHint + "）" : "")
                    + "。请先点击输入框使其获得焦点";
        }

        // Android 8+ 推荐用 performAction + ARGUMENT_SET_TEXT_CHARSEQUENCE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            target.recycle();
            return ok ? "已输入文本: " + text : "输入文本失败";
        } else {
            // 低版本：先清空再逐字输入
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, null);
            if (!ok) {
                target.recycle();
                return "清空输入框失败";
            }
            // 通过剪贴板方式输入
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("agent", text);
            clipboard.setPrimaryClip(clip);
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            target.recycle();
            return ok ? "已输入文本: " + text : "粘贴文本失败，请确认输入框已获焦";
        }
    }

    /**
     * 遍历并返回可访问性节点树，用于 AI 分析当前屏幕布局。
     */
    public String getUiTree(int maxDepth) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "无法获取当前窗口的 UI 树（可能没有前台界面）";
        }
        StringBuilder sb = new StringBuilder();
        buildTree(root, 0, maxDepth, sb);
        root.recycle();
        return sb.toString();
    }

    /**
     * 截图（需要 API 34+，即 Android 14）。
     */
    public String takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return "截图功能需要 Android 14+。当前系统版本不支持。";
        }
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            takeScreenshot(0, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (bitmap == null) {
                                    future.complete("截图失败：无法获取 Bitmap");
                                    return;
                                }
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                                byte[] bytes = bos.toByteArray();
                                bos.close();
                                result.getHardwareBuffer().close();
                                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                future.complete("data:image/jpeg;base64," + base64);
                            } catch (Exception e) {
                                future.complete("截图编码失败: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            future.complete("截图失败: error code " + errorCode);
                        }
                    });
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "截图失败: " + e.getMessage();
        }
    }

    /** 通过包名启动 App */
    public String launchApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "launch_app 命令需要 packageName 参数";
        }
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                return "未找到应用: " + packageName + "，请确认包名正确";
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return "已启动应用: " + packageName;
        } catch (Exception e) {
            return "启动应用失败: " + e.getMessage();
        }
    }

    /** 模拟系统按键 */
    public String pressKey(String key) {
        if (key == null) return "press_key 命令需要 key 参数";

        int action = switch (key.toLowerCase()) {
            case "back" -> GLOBAL_ACTION_BACK;
            case "home" -> GLOBAL_ACTION_HOME;
            case "recent", "recents" -> GLOBAL_ACTION_RECENTS;
            case "notification", "notifications" -> GLOBAL_ACTION_NOTIFICATIONS;
            case "power_dialog", "power" -> GLOBAL_ACTION_POWER_DIALOG;
            case "screenshot", "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT;
            case "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS;
            case "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN;
            default -> -1;
        };

        if (action == -1) {
            return "不支持的按键: " + key
                    + "。支持: back, home, recent, notification, power_dialog, screenshot, quick_settings, lock_screen";
        }

        boolean ok = performGlobalAction(action);
        return ok ? "已按下: " + key : "按键失败: " + key;
    }

    /** 滚动屏幕 */
    public String scroll(String direction) {
        if (direction == null) return "scroll 命令需要 direction 参数（forward/backward）";

        int action = switch (direction.toLowerCase()) {
            case "forward", "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            case "backward", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            default -> -1;
        };

        if (action == -1) {
            return "不支持的滚动方向: " + direction + "。支持: forward/down, backward/up";
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "无法获取当前窗口";
        }
        AccessibilityNodeInfo scrollable = findFirstScrollable(root);
        if (scrollable == null) {
            root.recycle();
            return "未找到可滚动的元素";
        }
        boolean ok = scrollable.performAction(action);
        scrollable.recycle();
        root.recycle();
        return ok ? "已滚动: " + direction : "滚动失败";
    }

    /** 等待指定文本出现（轮询 UI 树） */
    public String waitForText(String text, int timeoutMs) {
        if (text == null || text.isEmpty()) {
            return "wait_for_text 命令需要 text 参数";
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        long interval = 300;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo node = findNodeByText(text);
            if (node != null) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                node.recycle();
                return "文本 [" + text + "] 已出现在屏幕 ("
                        + bounds.centerX() + ", " + bounds.centerY() + ")";
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "等待被中断";
            }
        }
        return "超时（" + timeoutMs + "ms）：未找到文本 [" + text + "]";
    }

    /** 获取当前焦点节点的信息 */
    public String getFocusedNodeInfo() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "无当前窗口";
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused == null) {
            root.recycle();
            return "当前没有焦点元素";
        }
        String info = nodeToString(focused);
        focused.recycle();
        root.recycle();
        return info;
    }

    // ==================== 内部辅助方法 ====================

    /** 执行点击手势 */
    private void performClick(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    /** 执行手势并等待完成 */
    private boolean dispatchGestureAndWait(GestureDescription gesture) {
        try {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    future.complete(true);
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    future.complete(false);
                }
            }, null);
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "手势执行失败", e);
            return false;
        }
    }

    /** 按文本查找节点（优先可点击，其次可聚焦） */
    private AccessibilityNodeInfo findNodeByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        // 先用 findByText 精确查找
        String lowerText = text.trim().toLowerCase();
        AccessibilityNodeInfo result = findNodeByTextRecursive(root, lowerText);
        root.recycle();

        // 如果没找到，重新获取 root 再找（防止 recycle 后使用）
        if (result == null) {
            root = getRootInActiveWindow();
            if (root != null) {
                result = findNodeByTextRecursive(root, lowerText);
                root.recycle();
            }
        }
        return result;
    }

    private AccessibilityNodeInfo findNodeByTextRecursive(AccessibilityNodeInfo node, String lowerText) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;

            // 检查文本匹配
            CharSequence childText = child.getText();
            CharSequence childDesc = child.getContentDescription();
            boolean textMatch = childText != null && childText.toString().trim().toLowerCase().contains(lowerText);
            boolean descMatch = childDesc != null && childDesc.toString().trim().toLowerCase().contains(lowerText);

            if ((textMatch || descMatch) && child.isClickable()) {
                return child; // 不 recycle，调用方负责 recycle
            }

            // 递归查找
            AccessibilityNodeInfo found = findNodeByTextRecursive(child, lowerText);
            if (found != null) {
                child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    /** 按 hint 查找输入框 */
    private AccessibilityNodeInfo findNodeByHint(String hint) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        String lowerHint = hint.toLowerCase();
        AccessibilityNodeInfo result = findNodeByHintRecursive(root, lowerHint);
        root.recycle();
        return result;
    }

    private AccessibilityNodeInfo findNodeByHintRecursive(AccessibilityNodeInfo node, String lowerHint) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;

            if (child.isEditable()) {
                CharSequence hintText = child.getHintText();
                if (hintText != null && hintText.toString().toLowerCase().contains(lowerHint)) {
                    return child;
                }
            }

            AccessibilityNodeInfo found = findNodeByHintRecursive(child, lowerHint);
            if (found != null) {
                child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    /** 查找第一个可滚动节点 */
    private AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findFirstScrollable(child);
            if (found != null) {
                child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    /** 递归构建 UI 树 */
    private void buildTree(AccessibilityNodeInfo node, int depth, int maxDepth, StringBuilder sb) {
        if (depth > maxDepth) return;

        // 缩进
        sb.append("  ".repeat(Math.max(0, depth)));
        sb.append(nodeToString(node));
        sb.append("\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buildTree(child, depth + 1, maxDepth, sb);
                child.recycle();
            }
        }
    }

    /** 将节点格式化为人类可读字符串 */
    private String nodeToString(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        String className = node.getClassName() != null
                ? node.getClassName().toString().replace("android.widget.", "").replace("android.view.", "")
                : "?";
        sb.append("[").append(className);

        if (node.getText() != null && node.getText().length() > 0) {
            sb.append(" text=\"").append(ellipsis(node.getText().toString(), 50)).append("\"");
        }
        if (node.getContentDescription() != null && node.getContentDescription().length() > 0) {
            sb.append(" desc=\"").append(ellipsis(node.getContentDescription().toString(), 40)).append("\"");
        }
        if (node.getHintText() != null && node.getHintText().length() > 0) {
            sb.append(" hint=\"").append(ellipsis(node.getHintText().toString(), 30)).append("\"");
        }
        if (node.getViewIdResourceName() != null) {
            sb.append(" id=\"").append(ellipsis(node.getViewIdResourceName(), 40)).append("\"");
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append(" rect=[").append(bounds.left).append(",").append(bounds.top)
                .append(",").append(bounds.right).append(",").append(bounds.bottom).append("]");

        // 标记属性
        if (node.isClickable()) sb.append(" clickable");
        if (node.isScrollable()) sb.append(" scrollable");
        if (node.isEditable()) sb.append(" editable");
        if (node.isFocused()) sb.append(" focused");
        if (node.isChecked()) sb.append(" checked");

        sb.append("]");
        return sb.toString();
    }

    private static String ellipsis(String s, int maxLen) {
        if (s == null) return "";
        String escaped = s.replace("\n", "\\n").replace("\"", "\\\"");
        if (escaped.length() <= maxLen) return escaped;
        // 按 code point 截断，避免在 emoji/特殊字符的代理对中间切断
        int end = escaped.offsetByCodePoints(0, Math.min(maxLen - 1,
                escaped.codePointCount(0, escaped.length()) - 1));
        return escaped.substring(0, end) + "…";
    }
}
