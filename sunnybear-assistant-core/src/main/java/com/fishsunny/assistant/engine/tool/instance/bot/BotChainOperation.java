package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage bot_chain_tool 的单个操作节点模型。一个节点 = 一个原语动作（或语法糖）+ 可选延迟。
 *        采用「单 POJO + type 字段分派」而非多态反序列化，操作类型有限、实现最直观。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/27
 */

import lombok.Data;

/**
 * 操作链中的单个操作节点。
 * <p>
 * 节点通过 {@link #type} 声明原语类型，其余字段按类型取用：
 * <ul>
 *     <li>{@code mouse_move}   —— x, y（归一化 0-1）</li>
 *     <li>{@code mouse_down}   —— button（left/right/middle，默认 left）</li>
 *     <li>{@code mouse_up}     —— button</li>
 *     <li>{@code mouse_scroll} —— amount（滚轮格数，正下负上）</li>
 *     <li>{@code key_down}     —— keys（按键名或组合键，如 ctrl+c），按书写顺序按下</li>
 *     <li>{@code key_up}       —— keys，自动逆序抬起（与 key_down 配套）</li>
 *     <li>{@code type}         —— keys（语法糖：一次完成 key_down + key_up 的敲击）</li>
 *     <li>{@code input_text}   —— text（输入文本内容，支持任意 Unicode 含中文）</li>
 *     <li>{@code click}        —— button（语法糖：在当前位置按下+松开，不带坐标）</li>
 *     <li>{@code wait}         —— 无，仅靠 delay 表示等待时长</li>
 * </ul>
 * 所有节点都可用 {@link #delay} 指定本步执行完成后的额外等待毫秒数；
 * 为 null 时回落到链级 default_delay。<0 表示不延迟。
 * <p>
 * 设计原则：语法糖（type、click）的能力只能是底层原语组合的子集——糖只省步骤，不引入新能力、不带坐标。
 */
@Data
public class BotChainOperation {

    /** 原语类型或语法糖类型，取值见类注释 */
    private String type;

    /** 归一化 X 坐标（0-1，相对屏幕宽度），mouse_move 使用 */
    private Double x;

    /** 归一化 Y 坐标（0-1，相对屏幕高度），mouse_move 使用 */
    private Double y;

    /** 鼠标按键：left / right / middle，mouse_down、mouse_up、click 使用，缺省 left */
    private String button;

    /** 滚轮格数，正数向下、负数向上，mouse_scroll 使用 */
    private Integer amount;

    /** 按键名或组合键（+ 连接），key_down、key_up、type 使用，如 ctrl、ctrl+c */
    private String keys;

    /** 待输入的文本内容（支持任意 Unicode，含中文），input_text 使用 */
    private String text;

    /** 本步执行完成后的等待毫秒数；null 回落链级默认，<0 表示不延迟 */
    private Integer delay;
}
