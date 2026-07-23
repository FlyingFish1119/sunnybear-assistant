package com.fishsunny.agent.protocol;

/**
 * 服务端下发的命令（WebSocket → APK）
 * <pre>
 * {"id":"uuid","method":"click","params":{"x":500,"y":800}}
 * </pre>
 */
public class CommandRequest {
    /** 命令 ID，返回结果时原样带回 */
    public String id;
    /** 方法名：click, swipe, type, get_ui_tree, screenshot, launch_app, press_key, scroll */
    public String method;
    /** 方法参数 */
    public Params params;

    public static class Params {
        // click / long_press
        public Integer x;
        public Integer y;
        public String text;        // 按文本查找并点击
        public Integer duration;   // long_press 持续时间 ms

        // swipe
        public Integer x1;
        public Integer y1;
        public Integer x2;
        public Integer y2;

        // type
        public String inputText;
        public String targetHint;  // 目标输入框的 hint 文本（可选）

        // get_ui_tree
        public Integer maxDepth;   // 最大深度，默认 10

        // screenshot
        public String savePath;    // 可选，本地保存路径

        // launch_app
        public String packageName;

        // press_key
        public String key;         // back, home, recent, notification, power_dialog, screenshot

        // scroll
        public String direction;   // forward, backward

        // wait_for_text
        public Integer timeout;    // 超时 ms
    }
}
