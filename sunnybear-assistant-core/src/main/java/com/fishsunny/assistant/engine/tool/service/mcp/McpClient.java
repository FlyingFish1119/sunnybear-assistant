package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP 客户端统一契约：屏蔽 HTTP 与 stdio 两种传输的差异，
 *        McpClientService 面向本接口装配与调用，上层工具无感知。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import java.util.Map;

public interface McpClient {

    /** 查询工具清单（单页）；cursor 非空时请求下一页 */
    McpListToolsResult listTools(String cursor);

    /** 调用远程工具；arguments 为 null 时按空对象传递 */
    McpCallResult callTool(String toolName, Map<String, Object> arguments);

    /** 释放底层资源：HTTP 无状态可空实现，stdio 需终止子进程 */
    void close();
}
