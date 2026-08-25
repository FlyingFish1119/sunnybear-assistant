package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP CallToolResult.content 数组中的单项；本项目只消费文本内容，其余类型以 type 占位
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 仅保留 type 与 text 两个字段；image/resource 等类型的其它字段（data/mimeType/resource）被忽略，
 * 由调用方按 type 渲染占位。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpContentItem(String type, String text) {
}
