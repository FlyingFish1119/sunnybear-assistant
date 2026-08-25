package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP 工具元数据：tools/list 返回的单个工具（名称、描述、入参 Schema）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * 反序列化自 MCP ListToolsResult.tools 数组中的单项。
 * inputSchema 为 JSON Schema 对象树（Map 形态），未知字段（annotations/outputSchema 等）忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpTool(String name, String description, Map<String, Object> inputSchema) {
}
