package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP (Model Context Protocol) 客户端门面 —— 按 transport 为每个配置的 MCP Server 装配对应传输实现，
 *        负责工具清单汇总（含分页合并）与工具调用。连接懒加载、会话复用，会话失效由客户端自动重建。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 11:04
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class McpClientService {

    /** 工具分页拉取上限，防止服务端游标永不结束导致死循环 */
    private static final int MAX_PAGES = 100;

    /** serverName → 客户端，配置顺序即遍历顺序 */
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpClientService(McpProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        for (McpProperties.Client client : properties.getClients()) {
            clients.put(client.getServerName(), create(client, httpClient, objectMapper));
        }
    }

    private static McpClient create(McpProperties.Client client, HttpClient httpClient, ObjectMapper objectMapper) {
        return client.isStdio()
                ? new McpStdioClient(client, objectMapper)
                : new McpHttpClient(client, httpClient, objectMapper);
    }

    /** 拉取指定 server 的全部工具（自动遍历分页合并）；未配置的 serverName 抛 IllegalArgumentException */
    public McpListToolsResult listTools(String serverName) {
        McpClient client = requireClient(serverName);
        List<McpTool> all = new ArrayList<>();
        String cursor = null;
        int page = 0;
        do {
            McpListToolsResult result = client.listTools(cursor);
            if (result != null && result.tools() != null) {
                all.addAll(result.tools());
            }
            cursor = result == null ? null : result.nextCursor();
            if (cursor != null && ++page >= MAX_PAGES) {
                throw new McpException("MCP Server [" + serverName + "] 工具分页超过上限 " + MAX_PAGES);
            }
        } while (cursor != null);
        return new McpListToolsResult(all, null);
    }

    /** 调用指定 server 上的远程工具 */
    public McpCallResult callTool(String serverName, String toolName, Map<String, Object> args) {
        return requireClient(serverName).callTool(toolName, args);
    }

    /** 应用关闭时终止 stdio 子进程，避免进程泄漏 */
    @PreDestroy
    public void close() {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端 [{}] 失败: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private McpClient requireClient(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new IllegalArgumentException("MCP Server [" + serverName + "] 未配置");
        }
        return client;
    }
}
