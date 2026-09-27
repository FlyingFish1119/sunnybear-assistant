package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 键盘键位常量表与字符→键位解析。原 KeyboardInputTool 内嵌的 KEY_MAP / SHIFT_CHAR_MAP 抽取至此，
 *        供操作链的键盘原语（key_down / key_up / type）共用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/27
 */

import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 键盘键位工具类。
 * <p>
 * 维护「按键名称 → AWT keycode」与「Shift 字符 → 基础键」两张映射表，
 * 并提供按名称/字符解析 keycode 的静态方法。所有映射基于 US-QWERTY 布局。
 */
public final class KeyboardKeys {

    private KeyboardKeys() {
    }

    /** 按键名称到 KeyEvent keycode 的映射（名称统一小写） */
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

    /**
     * 按名称解析单个按键的 keycode。
     *
     * @param keyName 按键名称（大小写不敏感），如 enter、ctrl、f5
     * @return keycode；名称不支持时返回 null
     */
    public static Integer resolveKeyCode(String keyName) {
        if (keyName == null) {
            return null;
        }
        return KEY_MAP.get(keyName.trim().toLowerCase());
    }

    /**
     * 解析单个 ASCII 可打印字符对应的 keycode 及是否需要 Shift。
     * <p>
     * 大写字母与 Shift 字符（如 !、@、?）会自动标记 needsShift 并回落到基础键。
     *
     * @param c 待输入的字符
     * @return 解析结果；字符不受支持时返回 null
     */
    public static CharKey resolveChar(char c) {
        boolean needsShift = false;
        char baseChar = c;

        if (Character.isUpperCase(c)) {
            needsShift = true;
            baseChar = Character.toLowerCase(c);
        } else if (SHIFT_CHAR_MAP.containsKey(c)) {
            needsShift = true;
            baseChar = SHIFT_CHAR_MAP.get(c);
        }

        Integer keyCode = KEY_MAP.get(String.valueOf(baseChar));
        if (keyCode == null) {
            return null;
        }
        return new CharKey(keyCode, needsShift);
    }

    /**
     * 字符解析结果：keycode + 是否需要 Shift 修饰。
     */
    public record CharKey(int keyCode, boolean needsShift) {
    }
}
