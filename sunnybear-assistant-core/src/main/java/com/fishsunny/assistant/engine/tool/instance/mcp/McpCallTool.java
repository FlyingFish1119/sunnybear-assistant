package com.fishsunny.assistant.engine.tool.instance.mcp;

/*
 * @Usage 调用指定 MCP Server 上的远程工具，入参以 JSON 对象透传给 server，
 *        执行失败（isError=true）时通过 ToolExecuteException 上报错误详情
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 16:30
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.McpToolKit;
import com.fishsunny.assistant.engine.tool.service.mcp.McpCallResult;
import com.fishsunny.assistant.engine.tool.service.mcp.McpClientService;
import com.fishsunny.assistant.engine.tool.service.mcp.McpContentItem;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(McpToolKit.class)
@ConditionalOnExpression("${engine.tool.mcp.enable:true} && ${engine.tool.mcp.call.enable:true}")
public class McpCallTool implements ToolHandler {

    public static final String NAME = "mcp_call_tool";

    /** 远程工具执行超时时间（毫秒），需大于 MCP 协议本身的 requestTimeout */
    private static final Integer TIMEOUT_MS = 60_000;

    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public McpCallTool(McpClientService mcpClientService, ObjectMapper objectMapper) {
        this.mcpClientService = mcpClientService;
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("调用指定 MCP Server 上的远程工具。" +
                        "建议先用 mcp_list_tool 查询目标 server 的可用工具及入参 Schema。")
                .setRequired(List.of("serverName", "toolName"))
                .setTimeoutMs(TIMEOUT_MS);

        ToolRegister.Parameters serverNameParam = new ToolRegister.Parameters()
                .setParameterName("serverName")
                .setType("string")
                .setDescription("MCP Server 连接名，对应 application.yml 中 engine.tool.mcp.clients[].server-name，例如 mcp-server");

        ToolRegister.Parameters toolNameParam = new ToolRegister.Parameters()
                .setParameterName("toolName")
                .setType("string")
                .setDescription("要调用的 MCP 远程工具名，可通过 mcp_list_tool 查询");

        ToolRegister.Parameters argumentsParam = new ToolRegister.Parameters()
                .setParameterName("arguments")
                .setType("object")
                .setDescription("工具入参，JSON 对象形式，例如 {\"query\":\"天气\"}，需符合该工具的入参 Schema。无参工具可不传");

        register.setParameters(List.of(serverNameParam, toolNameParam, argumentsParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments = parseArguments(argumentsJson);

        Map<String, Object> remoteArgs = extractRemoteArgs(arguments.getArguments());

        McpCallResult result;
        try {
            result = mcpClientService.callTool(arguments.getServerName(), arguments.getToolName(), remoteArgs);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "调用 MCP Server [" + arguments.getServerName() + "] 的工具 [" + arguments.getToolName() + "] 失败: " + e.getMessage());
        }

        String content = extractContent(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolExecutor.ToolExecuteException(
                    "MCP 工具 [" + arguments.getToolName() + "] 执行失败: " + content);
        }
        return new ToolExecutor.ToolExecuteResponse(name(), StringUtils.hasText(content) ? content : "(工具无文本返回)");
    }

    /**
     * 提取远程工具入参。兼容两种形态：JSON 对象直接透传；
     * 若模型误传 JSON 字符串则尝试二次解析，仍失败才报错。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRemoteArgs(Object rawArgs) throws ToolExecutor.ToolExecuteException {
        if (rawArgs == null) {
            return Map.of();
        }
        if (rawArgs instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (rawArgs instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
            }
        }
        throw new ToolExecutor.ToolExecuteException("参数 arguments 应为 JSON 对象，例如 {\"query\":\"天气\"}");
    }

    /** 拼接工具返回的多段 content；仅提取文本，其余类型以 type 占位标注 */
    private String extractContent(McpCallResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (McpContentItem item : result.content()) {
            if (content.length() > 0) {
                content.append("\n");
            }
            if ("text".equals(item.type()) && StringUtils.hasText(item.text())) {
                content.append(item.text());
            } else {
                content.append("[非文本内容: ").append(item.type() == null ? "?" : item.type()).append("]");
            }
        }
        return content.toString();
    }

    private Arguments parseArguments(String argumentsJson) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(argumentsJson)) {
            throw new ToolExecutor.ToolExecuteException("参数不能为空，至少需要提供 serverName 与 toolName");
        }
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null || !StringUtils.hasText(arguments.getServerName()) || !StringUtils.hasText(arguments.getToolName())) {
                throw new ToolExecutor.ToolExecuteException("参数 serverName 与 toolName 不能为空");
            }
            return arguments;
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
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
        private String toolName;
        /** 接收任意 JSON 形态，由 extractRemoteArgs 兼容对象与字符串两种传法 */
        private Object arguments;
    }
}
