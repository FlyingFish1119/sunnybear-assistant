package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 键盘输入工具，支持单个按键、组合键和文本输入
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BotToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ToolKitComponent(BotToolKit.class)
@ConditionalOnExpression("${engine.tool.bot.enable:true} && ${engine.tool.bot.keyboard-input.enable:true}")
public class KeyboardInputTool implements ToolHandler {

    public static final String NAME = "keyboard_input_tool";

    /** 默认文本输入按键间隔（毫秒） */
    private static final int DEFAULT_TYPE_DELAY_MS = 20;
    /** 修饰键按下/释放间隔（毫秒） */
    private static final int MODIFIER_DELAY_MS = 20;
    /** 组合键内各键之间的默认间隔（毫秒） */
    private static final int COMBO_KEY_DELAY_MS = 10;

    /** 按键名称到 KeyEvent keycode 的映射 */
    private static final Map<String, Integer> KEY_MAP = new LinkedHashMap<>();

    /** 需要 Shift 修饰的字符到基础键的映射（US-QWERTY 布局） */
    private static final Map<Character, Character> SHIFT_CHAR_MAP = new LinkedHashMap<>();

    static {
        // ---- 字母键 A-Z ----
        KEY_MAP.put("a", KeyEvent.VK_A);
        KEY_MAP.put("b", KeyEvent.VK_B);
        KEY_MAP.put("c", KeyEvent.VK_C);
        KEY_MAP.put("d", KeyEvent.VK_D);
        KEY_MAP.put("e", KeyEvent.VK_E);
        KEY_MAP.put("f", KeyEvent.VK_F);
        KEY_MAP.put("g", KeyEvent.VK_G);
        KEY_MAP.put("h", KeyEvent.VK_H);
        KEY_MAP.put("i", KeyEvent.VK_I);
        KEY_MAP.put("j", KeyEvent.VK_J);
        KEY_MAP.put("k", KeyEvent.VK_K);
        KEY_MAP.put("l", KeyEvent.VK_L);
        KEY_MAP.put("m", KeyEvent.VK_M);
        KEY_MAP.put("n", KeyEvent.VK_N);
        KEY_MAP.put("o", KeyEvent.VK_O);
        KEY_MAP.put("p", KeyEvent.VK_P);
        KEY_MAP.put("q", KeyEvent.VK_Q);
        KEY_MAP.put("r", KeyEvent.VK_R);
        KEY_MAP.put("s", KeyEvent.VK_S);
        KEY_MAP.put("t", KeyEvent.VK_T);
        KEY_MAP.put("u", KeyEvent.VK_U);
        KEY_MAP.put("v", KeyEvent.VK_V);
        KEY_MAP.put("w", KeyEvent.VK_W);
        KEY_MAP.put("x", KeyEvent.VK_X);
        KEY_MAP.put("y", KeyEvent.VK_Y);
        KEY_MAP.put("z", KeyEvent.VK_Z);

        // ---- 数字键 0-9 ----
        KEY_MAP.put("0", KeyEvent.VK_0);
        KEY_MAP.put("1", KeyEvent.VK_1);
        KEY_MAP.put("2", KeyEvent.VK_2);
        KEY_MAP.put("3", KeyEvent.VK_3);
        KEY_MAP.put("4", KeyEvent.VK_4);
        KEY_MAP.put("5", KeyEvent.VK_5);
        KEY_MAP.put("6", KeyEvent.VK_6);
        KEY_MAP.put("7", KeyEvent.VK_7);
        KEY_MAP.put("8", KeyEvent.VK_8);
        KEY_MAP.put("9", KeyEvent.VK_9);

        // ---- 功能键 F1-F12 ----
        KEY_MAP.put("f1", KeyEvent.VK_F1);
        KEY_MAP.put("f2", KeyEvent.VK_F2);
        KEY_MAP.put("f3", KeyEvent.VK_F3);
        KEY_MAP.put("f4", KeyEvent.VK_F4);
        KEY_MAP.put("f5", KeyEvent.VK_F5);
        KEY_MAP.put("f6", KeyEvent.VK_F6);
        KEY_MAP.put("f7", KeyEvent.VK_F7);
        KEY_MAP.put("f8", KeyEvent.VK_F8);
        KEY_MAP.put("f9", KeyEvent.VK_F9);
        KEY_MAP.put("f10", KeyEvent.VK_F10);
        KEY_MAP.put("f11", KeyEvent.VK_F11);
        KEY_MAP.put("f12", KeyEvent.VK_F12);

        // ---- 数字小键盘 ----
        KEY_MAP.put("numpad0", KeyEvent.VK_NUMPAD0);
        KEY_MAP.put("numpad1", KeyEvent.VK_NUMPAD1);
        KEY_MAP.put("numpad2", KeyEvent.VK_NUMPAD2);
        KEY_MAP.put("numpad3", KeyEvent.VK_NUMPAD3);
        KEY_MAP.put("numpad4", KeyEvent.VK_NUMPAD4);
        KEY_MAP.put("numpad5", KeyEvent.VK_NUMPAD5);
        KEY_MAP.put("numpad6", KeyEvent.VK_NUMPAD6);
        KEY_MAP.put("numpad7", KeyEvent.VK_NUMPAD7);
        KEY_MAP.put("numpad8", KeyEvent.VK_NUMPAD8);
        KEY_MAP.put("numpad9", KeyEvent.VK_NUMPAD9);
        KEY_MAP.put("numpad_add", KeyEvent.VK_ADD);
        KEY_MAP.put("numpad_subtract", KeyEvent.VK_SUBTRACT);
        KEY_MAP.put("numpad_multiply", KeyEvent.VK_MULTIPLY);
        KEY_MAP.put("numpad_divide", KeyEvent.VK_DIVIDE);
        KEY_MAP.put("numpad_decimal", KeyEvent.VK_DECIMAL);

        // ---- 导航键 ----
        KEY_MAP.put("up", KeyEvent.VK_UP);
        KEY_MAP.put("down", KeyEvent.VK_DOWN);
        KEY_MAP.put("left", KeyEvent.VK_LEFT);
        KEY_MAP.put("right", KeyEvent.VK_RIGHT);
        KEY_MAP.put("home", KeyEvent.VK_HOME);
        KEY_MAP.put("end", KeyEvent.VK_END);
        KEY_MAP.put("page_up", KeyEvent.VK_PAGE_UP);
        KEY_MAP.put("page_down", KeyEvent.VK_PAGE_DOWN);

        // ---- 编辑键 ----
        KEY_MAP.put("enter", KeyEvent.VK_ENTER);
        KEY_MAP.put("return", KeyEvent.VK_ENTER);
        KEY_MAP.put("space", KeyEvent.VK_SPACE);
        KEY_MAP.put("tab", KeyEvent.VK_TAB);
        KEY_MAP.put("escape", KeyEvent.VK_ESCAPE);
        KEY_MAP.put("esc", KeyEvent.VK_ESCAPE);
        KEY_MAP.put("backspace", KeyEvent.VK_BACK_SPACE);
        KEY_MAP.put("delete", KeyEvent.VK_DELETE);
        KEY_MAP.put("del", KeyEvent.VK_DELETE);
        KEY_MAP.put("insert", KeyEvent.VK_INSERT);
        KEY_MAP.put("ins", KeyEvent.VK_INSERT);

        // ---- 锁定键 ----
        KEY_MAP.put("caps_lock", KeyEvent.VK_CAPS_LOCK);
        KEY_MAP.put("num_lock", KeyEvent.VK_NUM_LOCK);
        KEY_MAP.put("scroll_lock", KeyEvent.VK_SCROLL_LOCK);

        // ---- 其他特殊键 ----
        KEY_MAP.put("print_screen", KeyEvent.VK_PRINTSCREEN);
        KEY_MAP.put("prtsc", KeyEvent.VK_PRINTSCREEN);
        KEY_MAP.put("pause", KeyEvent.VK_PAUSE);
        KEY_MAP.put("break", KeyEvent.VK_PAUSE);
        KEY_MAP.put("context_menu", KeyEvent.VK_CONTEXT_MENU);
        KEY_MAP.put("apps", KeyEvent.VK_CONTEXT_MENU);

        // ---- 修饰键 ----
        KEY_MAP.put("ctrl", KeyEvent.VK_CONTROL);
        KEY_MAP.put("control", KeyEvent.VK_CONTROL);
        KEY_MAP.put("alt", KeyEvent.VK_ALT);
        KEY_MAP.put("shift", KeyEvent.VK_SHIFT);
        KEY_MAP.put("win", KeyEvent.VK_WINDOWS);
        KEY_MAP.put("windows", KeyEvent.VK_WINDOWS);
        KEY_MAP.put("cmd", KeyEvent.VK_WINDOWS);
        KEY_MAP.put("meta", KeyEvent.VK_META);

        // ---- 符号键（基础字符） ----
        KEY_MAP.put("`", KeyEvent.VK_BACK_QUOTE);
        KEY_MAP.put("-", KeyEvent.VK_MINUS);
        KEY_MAP.put("=", KeyEvent.VK_EQUALS);
        KEY_MAP.put("[", KeyEvent.VK_OPEN_BRACKET);
        KEY_MAP.put("]", KeyEvent.VK_CLOSE_BRACKET);
        KEY_MAP.put("\\", KeyEvent.VK_BACK_SLASH);
        KEY_MAP.put(";", KeyEvent.VK_SEMICOLON);
        KEY_MAP.put("'", KeyEvent.VK_QUOTE);
        KEY_MAP.put(",", KeyEvent.VK_COMMA);
        KEY_MAP.put(".", KeyEvent.VK_PERIOD);
        KEY_MAP.put("/", KeyEvent.VK_SLASH);

        // ---- Shift 字符映射（US-QWERTY 布局）：Shift+字符 → 基础键 ----
        SHIFT_CHAR_MAP.put('~', '`');
        SHIFT_CHAR_MAP.put('!', '1');
        SHIFT_CHAR_MAP.put('@', '2');
        SHIFT_CHAR_MAP.put('#', '3');
        SHIFT_CHAR_MAP.put('$', '4');
        SHIFT_CHAR_MAP.put('%', '5');
        SHIFT_CHAR_MAP.put('^', '6');
        SHIFT_CHAR_MAP.put('&', '7');
        SHIFT_CHAR_MAP.put('*', '8');
        SHIFT_CHAR_MAP.put('(', '9');
        SHIFT_CHAR_MAP.put(')', '0');
        SHIFT_CHAR_MAP.put('_', '-');
        SHIFT_CHAR_MAP.put('+', '=');
        SHIFT_CHAR_MAP.put('{', '[');
        SHIFT_CHAR_MAP.put('}', ']');
        SHIFT_CHAR_MAP.put('|', '\\');
        SHIFT_CHAR_MAP.put(':', ';');
        SHIFT_CHAR_MAP.put('"', '\'');
        SHIFT_CHAR_MAP.put('<', ',');
        SHIFT_CHAR_MAP.put('>', '.');
        SHIFT_CHAR_MAP.put('?', '/');
    }

    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public KeyboardInputTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        // 构建支持的按键列表（用于描述）
        StringBuilder keyListBuilder = new StringBuilder();
        KEY_MAP.keySet().stream()
                .filter(k -> !k.contains("_") || k.startsWith("numpad"))
                .distinct()
                .limit(30)
                .forEach(k -> keyListBuilder.append(k).append(", "));

        String description = "模拟键盘输入。支持三种方式：1) 单个按键（enter、escape）；2) 组合键（ctrl+c）；3) 文本字符串输入。注意：中文输入法可能导致输入异常。";

        register = new ToolRegister()
                .setName(NAME)
                .setDescription(description)
                .setRequired(List.of("keys"));

        ToolRegister.Parameters keysParam = new ToolRegister.Parameters()
                .setParameterName("keys")
                .setType("string")
                .setDescription("（可选）要按下的按键名称或组合键。单个按键如 'enter'、'escape'、'tab'、'a'。"
                        + "组合键使用 '+' 连接，如 'ctrl+c'（复制）、'ctrl+v'（粘贴）、'alt+tab'（切换窗口）、'ctrl+shift+escape'（任务管理器）。"
                        + "注意：组合键中的修饰键(ctrl/alt/shift/win)会自动处理按下和释放顺序。");

        ToolRegister.Parameters textParam = new ToolRegister.Parameters()
                .setParameterName("text")
                .setType("string")
                .setDescription("（可选）要输入的文本字符串。如果提供了此参数，则会逐字符输入文本，"
                        + "自动处理大小写和需要 Shift 的符号。当前仅支持 ASCII 可打印字符。");

        ToolRegister.Parameters delayParam = new ToolRegister.Parameters()
                .setParameterName("delay")
                .setType("integer")
                .setDescription("（可选）文本输入时每个字符之间的延迟（毫秒），默认 " + DEFAULT_TYPE_DELAY_MS + "ms。"
                        + "值越大输入越慢但更稳定，建议在 10-100ms 之间。");

        register.setParameters(List.of(keysParam, textParam, delayParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            boolean hasKeys = StringUtils.hasText(arguments.getKeys());
            boolean hasText = StringUtils.hasText(arguments.getText());

            if (!hasKeys && !hasText) {
                throw new ToolExecutor.ToolExecuteException("参数 keys 和 text 至少需要提供一个");
            }

            int delay = DEFAULT_TYPE_DELAY_MS;
            if (StringUtils.hasText(arguments.getDelay())) {
                delay = Integer.parseInt(arguments.getDelay());
                if (delay < 0) {
                    throw new ToolExecutor.ToolExecuteException("delay 不能为负数");
                }
                if (delay > 1000) {
                    throw new ToolExecutor.ToolExecuteException("delay 不能超过 1000ms");
                }
            }

            Robot robot = new Robot();

            if (hasText) {
                // 文本输入模式
                String text = arguments.getText();
                typeText(robot, text, delay);
                return new ToolExecutor.ToolExecuteResponse(
                        name(),
                        "已输入文本（" + text.length() + " 个字符）"
                );
            } else {
                // 按键模式（单个键或组合键）
                String keysStr = arguments.getKeys().trim();
                String result = tapKeys(robot, keysStr);
                return new ToolExecutor.ToolExecuteResponse(name(), result);
            }

        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 解析并执行按键组合
     * 组合键格式：key1+key2+key3，修饰键(ctrl/alt/shift/win/meta)先按下，然后按下普通键，最后按相反顺序释放
     */
    private String tapKeys(Robot robot, String keysStr) throws ToolExecutor.ToolExecuteException {
        String[] parts = keysStr.split("\\+");
        if (parts.length == 0) {
            throw new ToolExecutor.ToolExecuteException("按键不能为空");
        }

        // 去除空白并转为小写用于匹配
        String[] keyNames = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            keyNames[i] = parts[i].trim().toLowerCase();
        }

        // 验证所有按键
        int[] keyCodes = new int[keyNames.length];
        for (int i = 0; i < keyNames.length; i++) {
            Integer code = KEY_MAP.get(keyNames[i]);
            if (code == null) {
                throw new ToolExecutor.ToolExecuteException("不支持的按键 [" + keyNames[i] + "]");
            }
            keyCodes[i] = code;
        }

        // 分离修饰键和普通键
        // 修饰键在组合键中通常先按下后释放
        StringBuilder description = new StringBuilder();

        try {
            // 按下所有键（修饰键先保持按下状态）
            for (int i = 0; i < keyCodes.length; i++) {
                robot.keyPress(keyCodes[i]);
                if (i < keyCodes.length - 1) {
                    robot.delay(COMBO_KEY_DELAY_MS);
                }
            }

            // 等待一下确保系统识别
            robot.delay(MODIFIER_DELAY_MS);

            // 按相反顺序释放所有键
            for (int i = keyCodes.length - 1; i >= 0; i--) {
                robot.keyRelease(keyCodes[i]);
                if (i > 0) {
                    robot.delay(COMBO_KEY_DELAY_MS);
                }
            }

            description.append("已执行按键组合: ").append(keysStr);
        } catch (Exception e) {
            // 确保释放所有已按下的键
            for (int code : keyCodes) {
                try {
                    robot.keyRelease(code);
                } catch (Exception ignored) {
                }
            }
            throw e;
        }

        return description.toString();
    }

    /**
     * 逐字符输入文本
     * 支持 ASCII 可打印字符 (32-126)，自动处理 Shift 修饰
     */
    private void typeText(Robot robot, String text, int delay) throws ToolExecutor.ToolExecuteException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                // 换行 → Enter
                tapKey(robot, KeyEvent.VK_ENTER);
            } else if (c == '\r') {
                // 回车 → 跳过（通常 \r\n 一起出现，\n 已处理）
                continue;
            } else if (c == '\t') {
                // Tab
                tapKey(robot, KeyEvent.VK_TAB);
            } else if (c == ' ') {
                // 空格
                tapKey(robot, KeyEvent.VK_SPACE);
            } else if (c < 32 || c > 126) {
                // 非 ASCII 可打印字符，跳过
                continue;
            } else {
                typeChar(robot, c);
            }

            if (i < text.length() - 1) {
                robot.delay(delay);
            }
        }
    }

    /**
     * 输入单个 ASCII 可打印字符，自动处理 Shift 修饰
     */
    private void typeChar(Robot robot, char c) throws ToolExecutor.ToolExecuteException {
        boolean needsShift = false;
        char baseChar = c;

        // 检查是否需要 Shift
        if (Character.isUpperCase(c)) {
            needsShift = true;
            baseChar = Character.toLowerCase(c);
        } else if (SHIFT_CHAR_MAP.containsKey(c)) {
            needsShift = true;
            baseChar = SHIFT_CHAR_MAP.get(c);
        }

        Integer keyCode = KEY_MAP.get(String.valueOf(baseChar));
        if (keyCode == null) {
            throw new ToolExecutor.ToolExecuteException("不支持的字符 [" + c + "]，无法映射到键盘按键");
        }

        try {
            if (needsShift) {
                robot.keyPress(KeyEvent.VK_SHIFT);
            }
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            if (needsShift) {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        } catch (Exception e) {
            // 确保释放 Shift 键
            if (needsShift) {
                try {
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    /**
     * 按下并释放单个按键（无修饰）
     */
    private void tapKey(Robot robot, int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    public static class Arguments {
        private String keys;
        private String text;
        private String delay;
    }
}
