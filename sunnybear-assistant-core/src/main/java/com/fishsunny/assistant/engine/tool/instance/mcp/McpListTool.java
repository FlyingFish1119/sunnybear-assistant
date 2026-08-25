package com.fishsunny.assistant.engine.tool.instance.mcp;

/*
 * @Usage 列出 MCP Server 提供的工具清单（名称、描述、入参 Schema），
 *        不传 serverName 时遍历 application.yml 中所有已配置的 MCP Server
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 16:30
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.McpToolKit;
import com.fishsunny.assistant.engine.tool.service.mcp.McpClientService;
import com.fishsunny.assistant.engine.tool.service.mcp.McpListToolsResult;
import com.fishsunny.assistant.engine.tool.service.mcp.McpProperties;
import com.fishsunny.assistant.engine.tool.service.mcp.McpTool;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(McpToolKit.class)
@ConditionalOnExpression("${engine.tool.mcp.enable:true} && ${engine.tool.mcp.list.enable:true}")
public class McpListTool implements ToolHandler {

    public static final String NAME = "mcp_list_tool";

    /** 单次清单查询超时时间（毫秒） */
    private static final Integer TIMEOUT_MS = 30_000;

    private final McpClientService mcpClientService;
    private final McpProperties mcpProperties;
    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public McpListTool(McpClientService mcpClientService, McpProperties mcpProperties, ObjectMapper objectMapper) {
        this.mcpClientService = mcpClientService;
        this.mcpProperties = mcpProperties;
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("列出 MCP Server 提供的工具清单（名称、描述、入参 Schema）。" +
                        "不传 serverName 时遍历所有已配置的 MCP Server。" +
                        "调用远程工具前建议先用本工具查询目标 server 的可用工具及入参结构。")
                .setRequired(List.of())
                .setTimeoutMs(TIMEOUT_MS);

        ToolRegister.Parameters serverNameParam = new ToolRegister.Parameters()
                .setParameterName("serverName")
                .setType("string")
                .setDescription("MCP Server 连接名，对应 application.yml 中 engine.tool.mcp.clients[].server-name，例如 mcp-server。不传则列出全部 server");

        register.setParameters(List.of(serverNameParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments = parseArguments(argumentsJson);
        String serverName = arguments == null ? null : arguments.getServerName();

        // 指定了 serverName 只查该 server，否则遍历全部已配置的 server
        List<McpProperties.Client> targets = new ArrayList<>();
        if (StringUtils.hasText(serverName)) {
            McpProperties.Client target = mcpProperties.getClients().stream()
                    .filter(client -> serverName.equals(client.getServerName()))
                    .findFirst()
                    .orElseThrow(() -> new ToolExecutor.ToolExecuteException(
                            "未找到名为 [" + serverName + "] 的 MCP Server 配置，可用的有: " + serverNames()));
            targets.add(target);
        } else {
            targets.addAll(mcpProperties.getClients());
        }
        if (targets.isEmpty()) {
            throw new ToolExecutor.ToolExecuteException("application.yml 中未配置任何 MCP Server（engine.tool.mcp.clients）");
        }

        StringBuilder output = new StringBuilder();
        for (McpProperties.Client target : targets) {
            output.append(renderServer(target)).append("\n");
        }
        return new ToolExecutor.ToolExecuteResponse(name(), output.toString().trim());
    }

    /** 渲染单个 server 的工具清单；查询失败不影响其余 server */
    private String renderServer(McpProperties.Client target) {
        StringBuilder output = new StringBuilder("## MCP Server [").append(target.getServerName()).append("]");
        try {
            McpListToolsResult result = mcpClientService.listTools(target.getServerName());
            List<McpTool> tools = result.tools() == null ? List.of() : result.tools();
            if (tools.isEmpty()) {
                return output.append("：未提供任何工具").toString();
            }
            output.append("：共 ").append(tools.size()).append(" 个工具\n");
            for (int i = 0; i < tools.size(); i++) {
                McpTool tool = tools.get(i);
                output.append(i + 1).append(". ").append(tool.name());
                if (StringUtils.hasText(tool.description())) {
                    output.append(" — ").append(tool.description());
                }
                output.append("\n");
                if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
                    output.append("   入参 Schema: ").append(writeJson(tool.inputSchema())).append("\n");
                }
            }
            if (StringUtils.hasText(result.nextCursor())) {
                output.append("注意：该 server 尚有更多工具因分页未展示\n");
            }
        } catch (Exception e) {
            output.append("：查询失败 — ").append(e.getMessage());
        }
        return output.toString();
    }

    private String serverNames() {
        return mcpProperties.getClients().stream()
                .map(McpProperties.Client::getServerName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(无)");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Arguments parseArguments(String argumentsJson) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(argumentsJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    private static class Arguments {
        private String serverName;
    }
}
