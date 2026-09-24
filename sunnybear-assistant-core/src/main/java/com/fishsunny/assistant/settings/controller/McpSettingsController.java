package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.tool.service.mcp.McpClientService;
import com.fishsunny.assistant.settings.McpSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * MCP Server 配置控制器
 * <p>
 * 提供 MCP Server 配置的查询与保存接口，保存后热生效。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class McpSettingsController {

    private static final Logger log = LoggerFactory.getLogger(McpSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String mcpSettingsPath;
    private final McpSettings mcpSettings;
    private final McpClientService mcpClientService;

    public McpSettingsController(
            ObjectMapper objectMapper,
            @Value("${mcp-settings.path:settings/mcp_settings.json}") String mcpSettingsPath,
            McpSettings mcpSettings,
            McpClientService mcpClientService) {
        this.objectMapper = objectMapper;
        this.mcpSettingsPath = mcpSettingsPath;
        this.mcpSettings = mcpSettings;
        this.mcpClientService = mcpClientService;
    }

    // ==================== MCP Server 配置 ====================

    @RequestMapping("/mcp/get")
    public RestResponse getMcpSettings() {
        return new RestResponse().success(mcpSettings);
    }

    /**
     * 保存 MCP Server 配置并热生效：校验通过后落盘 mcp_settings.json，
     * 再让 McpClientService 重建全部连接（含关闭旧的 stdio 子进程），无需重启。
     */
    @PostMapping("/mcp/save")
    public RestResponse saveMcpSettings(@RequestBody(required = false) McpSettings settings) {
        if (settings == null || settings.getClients() == null) {
            return new RestResponse().error("Invalid settings");
        }

        List<McpSettings.Client> sanitized = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (McpSettings.Client client : settings.getClients()) {
            if (client == null || !StringUtils.hasText(client.getServerName())) {
                return new RestResponse().error("每个 MCP Server 都需要填写 serverName");
            }
            String serverName = client.getServerName().trim();
            if (!names.add(serverName)) {
                return new RestResponse().error("MCP Server 名称重复: " + serverName);
            }
            client.setServerName(serverName);

            boolean stdio = client.isStdio();
            client.setTransport(stdio ? "stdio" : "http");
            if (stdio) {
                if (!StringUtils.hasText(client.getCommand())) {
                    return new RestResponse().error("MCP Server [" + serverName + "] transport=stdio 需要填写 command");
                }
            } else if (!StringUtils.hasText(client.getUrl())) {
                return new RestResponse().error("MCP Server [" + serverName + "] transport=http 需要填写 url");
            }

            if (client.getTimeoutS() == null || client.getTimeoutS() <= 0) {
                client.setTimeoutS(20);
            }
            if (client.getArgs() == null) {
                client.setArgs(new ArrayList<>());
            }
            if (client.getEnv() == null) {
                client.setEnv(new LinkedHashMap<>());
            }
            sanitized.add(client);
        }

        mcpSettings.setClients(sanitized);

        File settingsFile = new File(mcpSettingsPath);
        File parent = settingsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.error("创建 MCP 设置目录失败: {}", parent.getAbsolutePath());
            return new RestResponse().error("保存失败");
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile, mcpSettings);
        } catch (Exception e) {
            log.error("保存 MCP 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }

        mcpClientService.reload(mcpSettings);
        return new RestResponse().success("保存成功");
    }
}
