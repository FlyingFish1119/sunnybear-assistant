package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP tools/call 结果：content 内容数组 + isError 错误标记
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * isError 字段使用包装类型 Boolean：服务端省略时为 null，调用方用 Boolean.TRUE.equals(...) 判空即可。
 * 注意：组件名 isError 的隐式属性名推导可能被 Jackson 剥掉 is 前缀，必须显式 @JsonProperty("isError")。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallResult(List<McpContentItem> content, @JsonProperty("isError") Boolean isError) {
}
