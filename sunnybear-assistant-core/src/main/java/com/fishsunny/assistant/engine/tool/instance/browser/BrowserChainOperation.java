package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage browser_chain_tool 的单个操作节点模型。一个节点 = 一个浏览器原语 + 可选延迟。
 *        采用「单 POJO + type 字段分派」而非多态反序列化，操作类型有限、实现最直观。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/9
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 浏览器操作链中的单个操作节点。
 * <p>
 * 节点通过 {@link #type} 声明原语类型，其余字段按类型取用：
 * <ul>
 *     <li>{@code click}    —— selector</li>
 *     <li>{@code type}     —— selector、text（清空后填入）</li>
 *     <li>{@code hover}    —— selector</li>
 *     <li>{@code select}   —— selector、value（选项 value）或 label（选项可见文本）</li>
 *     <li>{@code drag}     —— source、target（CSS 选择器）</li>
 *     <li>{@code scroll}   —— selector（可选，缺省滚整页）、delta_y（像素，正下负上）</li>
 *     <li>{@code wait_for} —— selector、timeout_ms（可选）</li>
 *     <li>{@code wait}     —— 无，仅靠 delay 表示等待时长</li>
 *     <li>{@code screenshot} —— 无参数，截取当前页面并随结果返回图片</li>
 *     <li>{@code eval}     —— script（在页面中执行 JavaScript，返回值写入链日志；执行前需用户确认）</li>
 * </ul>
 * 所有节点都可用 {@link #delay} 指定本步执行完成后的额外等待毫秒数；
 * 为 null 时回落到链级 default_delay。&lt;0 表示不延迟。
 */
@Data
public class BrowserChainOperation {

    /** 原语类型，取值见类注释 */
    private String type;

    /** CSS 选择器，click / type / hover / select / scroll / wait_for 使用 */
    private String selector;

    /** 待填入的文本内容，type 使用 */
    private String text;

    /** 源元素 CSS 选择器（被拖拽方），drag 使用 */
    private String source;

    /** 目标元素 CSS 选择器（拖拽落点），drag 使用 */
    private String target;

    /** 下拉选项的 value，select 使用 */
    private String value;

    /** 下拉选项的可见文本，select 使用；提供时优先于 value */
    private String label;

    /** 垂直滚动像素数，正下负上，scroll 使用，缺省 300 */
    @JsonProperty("delta_y")
    private Integer deltaY;

    /** 等待元素出现的超时毫秒数，wait_for 使用，缺省 10000 */
    @JsonProperty("timeout_ms")
    private Integer timeoutMs;

    /** 要执行的 JavaScript 代码，eval 使用；返回值会写入链日志 */
    private String script;

    /** 本步执行完成后的等待毫秒数；null 回落链级默认，<0 表示不延迟 */
    private Integer delay;
}
