package com.fishsunny.assistant.engine.tool.instance.mcp;

/*
 * @Usage 调用指定 MCP Server 上的远程工具，入参以 JSON 对象透传给 server，
 *        执行失败（isError=true）时通过 ToolExecuteException 上报错误详情。
 *        文本内容作为结果文本返回；图片内容以多模态 content 数组交给上层模型直接查看。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 16:30
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.McpToolKit;
import com.fishsunny.assistant.engine.tool.service.mcp.McpCallResult;
import com.fishsunny.assistant.engine.tool.service.mcp.McpClientService;
import com.fishsunny.assistant.engine.tool.service.mcp.McpContentItem;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ToolKitComponent(McpToolKit.class)
@ConditionalOnExpression("${engine.tool.mcp.enable:true} && ${engine.tool.mcp.call.enable:true}")
public class McpCallTool implements ToolHandler, MultimodalResultAble {

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
                        "建议先用 mcp_list_tool 查询目标 server 的可用工具及入参 Schema。" +
                        "工具返回的图片会以多模态内容直接提供给你查看。")
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

    /**
     * 需要 chatSession 上下文：图片等二进制结果由 ToolExecutor 统一落盘到会话文件目录，
     * 落盘依赖 context 中的当前会话，缺失会直接失败。
     */
    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
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

        StringBuilder text = new StringBuilder();
        List<McpContentItem> images = new ArrayList<>();
        collectContent(result, text, images);

        String resultText = text.length() == 0 ? "(工具无文本返回)" : text.toString();

        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolExecutor.ToolExecuteException(
                    "MCP 工具 [" + arguments.getToolName() + "] 执行失败: " + resultText);
        }

        ToolExecutor.ToolExecuteResponse response = new ToolExecutor.ToolExecuteResponse(name(), resultText);
        for (McpContentItem image : images) {
            String fileName = UUID.randomUUID() + "." + imageSuffix(image.mimeType());
            response.modalContent(fileName, ContentTypeVariable.IMAGE, image.data().trim());
        }
        return response;
    }

    /** 拆分 MCP 返回的 content 数组：文本拼成结果文本，图片收集起来待落盘回传 */
    private void collectContent(McpCallResult result, StringBuilder text, List<McpContentItem> images) {
        if (result.content() == null) {
            return;
        }
        for (McpContentItem item : result.content()) {
            if ("text".equals(item.type()) && StringUtils.hasText(item.text())) {
                appendLine(text, item.text());
            } else if (isImage(item)) {
                images.add(item);
                appendLine(text, "[图片已附带，可直接查看]");
            } else {
                appendLine(text, "[非文本内容: " + (item.type() == null ? "?" : item.type()) + "]");
            }
        }
    }

    private boolean isImage(McpContentItem item) {
        return "image".equals(item.type()) && StringUtils.hasText(item.data());
    }

    /** mimeType 映射为文件后缀；MCP 常见取值为 image/png、image/jpeg，缺省按 png 处理 */
    private String imageSuffix(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "png";
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpg";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        if (lower.contains("webp")) {
            return "webp";
        }
        if (lower.contains("bmp")) {
            return "bmp";
        }
        return "png";
    }

    private void appendLine(StringBuilder text, String line) {
        if (text.length() > 0) {
            text.append("\n");
        }
        text.append(line);
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
