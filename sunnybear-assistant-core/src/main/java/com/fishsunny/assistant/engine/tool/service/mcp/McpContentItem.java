package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP CallToolResult.content 数组中的单项：文本内容进结果文本，
 *        图片内容（type=image）保留 base64 与 mimeType，交由工具层以多模态形式回传。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 保留 type / text / data / mimeType 四个字段。
 * MCP 的图片内容形如 {"type":"image","data":"&lt;base64&gt;","mimeType":"image/png"}；
 * resource / resource_link 等类型的其它字段仍被忽略，由调用方按 type 渲染占位。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpContentItem(String type, String text, String data, String mimeType) {
}
