package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP tools/list 单页结果：工具列表 + 分页游标 nextCursor（为 null 表示已到末页）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpListToolsResult(List<McpTool> tools, String nextCursor) {
}
