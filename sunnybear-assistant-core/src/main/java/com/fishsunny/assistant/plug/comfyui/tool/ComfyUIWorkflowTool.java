package com.fishsunny.assistant.plug.comfyui.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * ComfyUI 工作流文件管理工具。
 * <p>
 * 支持两个操作，均由 Agent 端在本地文件系统执行：
 * <ul>
 *   <li><b>list</b> — 列出工作流目录下所有 .json 文件及基本信息</li>
 *   <li><b>detail</b> — 读取指定工作流文件的完整 JSON 内容</li>
 * </ul>
 * 工作流目录路径在 Agent 端通过 {@code --workflow-path} 参数配置（默认 ./workflow）。
 */
@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.workflow.enable:true}")
public class ComfyUIWorkflowTool implements ToolHandler {

    public static final String NAME = "comfyui_workflow";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final ComfyUIBridgeService bridgeService;

    public ComfyUIWorkflowTool(ObjectMapper objectMapper, ComfyUIBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        管理 ComfyUI 工作流文件。支持两个操作：\
                        list — 列出所有可用工作流文件名称及节点信息；\
                        detail — 获取指定工作流文件的完整 JSON 内容。\
                        使用前先 list 查看有哪些工作流，再用 detail 获取具体内容。""")
                .setRequired(List.of("action"));

        register.setParameters(List.of(
                new ToolRegister.Parameters("action", "string",
                        "操作类型：list（列出所有工作流）或 detail（获取指定工作流详情）"),
                new ToolRegister.Parameters("workflowName", "string",
                        "工作流文件名，仅在 action=detail 时必填")
        ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args == null || !StringUtils.hasText(args.getAction())) {
                throw new ToolExecutor.ToolExecuteException("参数 action 不能为空，可选值: list, detail");
            }

            String action = args.getAction().trim().toLowerCase();

            return switch (action) {
                case "list" -> {
                    String result = bridgeService.sendCommand("workflow-list", "{}");
                    yield new ToolExecutor.ToolExecuteResponse(NAME, result);
                }
                case "detail" -> {
                    if (!StringUtils.hasText(args.getWorkflowName())) {
                        throw new ToolExecutor.ToolExecuteException("参数 workflowName 不能为空（action=detail 时必填）");
                    }
                    String paramsJson = objectMapper.writeValueAsString(
                            Map.of("workflowName", args.getWorkflowName().trim()));
                    String result = bridgeService.sendCommand("workflow-detail", paramsJson);
                    yield new ToolExecutor.ToolExecuteResponse(NAME, result);
                }
                default -> throw new ToolExecutor.ToolExecuteException(
                        "不支持的操作: " + action + "，可选值: list, detail");
            };
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("工作流操作失败: " + e.getMessage());
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
    @Accessors(chain = true)
    private static class Arguments {
        private String action;
        private String workflowName;
    }
}
