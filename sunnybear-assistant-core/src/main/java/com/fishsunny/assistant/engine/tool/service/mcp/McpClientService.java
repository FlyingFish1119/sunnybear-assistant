package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP (Model Context Protocol) 客户端门面 —— 按 transport 为每个配置的 MCP Server 装配对应传输实现，
 *        负责工具清单汇总（含分页合并）与工具调用。连接懒加载、会话复用，会话失效由客户端自动重建。
 *        配置来源为 settings/mcp_settings.json；设置页保存后调用 {@link #reload} 热替换，无需重启。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 11:04
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.McpSettings;
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

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** serverName → 客户端，配置顺序即遍历顺序 */
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    /** 当前配置快照；reload 时整体替换 */
    private volatile List<McpSettings.Client> configs = List.of();

    public McpClientService(McpSettings settings, HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        reload(settings);
    }

    /**
     * 用最新配置重建全部客户端：先关掉旧连接（含 stdio 子进程），再按新配置装配。
     * 设置页保存后调用，使改动立即生效。
     */
    public synchronized void reload(McpSettings settings) {
        closeAll();
        clients.clear();
        configs = settings == null || settings.getClients() == null
                ? List.of()
                : List.copyOf(settings.getClients());
        for (McpSettings.Client client : configs) {
            clients.put(client.getServerName(), create(client));
        }
        log.info("MCP 客户端已装配 {} 个 Server: {}", clients.size(), clients.keySet());
    }

    private McpClient create(McpSettings.Client client) {
        return client.isStdio()
                ? new McpStdioClient(client, objectMapper)
                : new McpHttpClient(client, httpClient, objectMapper);
    }

    /** 当前已配置的 Server 列表（顺序即配置顺序），供清单工具枚举 */
    public List<McpSettings.Client> clients() {
        return configs;
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
        closeAll();
    }

    private void closeAll() {
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
