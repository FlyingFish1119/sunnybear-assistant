package com.fishsunny.assistant.plug.comfyui.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * ComfyUI 工作流文件管理工具。
 * <p>
 * 目录由核心端配置（{@code plug.comfyui.workflow-dir}），要求存放 API 格式的 .json：
 * <ul>
 *   <li><b>list</b> — 列出所有工作流文件及节点信息</li>
 *   <li><b>detail</b> — 读取指定工作流的完整 JSON</li>
 * </ul>
 * 读写由卫星机上的 Python 脚本完成，不再依赖专用代理。
 * </p>
 */
@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.workflow.enable:true}")
public class ComfyUIWorkflowTool implements ToolHandler {

    public static final String NAME = "comfyui_workflow";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final ComfyUIService comfyUIService;

    public ComfyUIWorkflowTool(ObjectMapper objectMapper, ComfyUIService comfyUIService) {
        this.objectMapper = objectMapper;
        this.comfyUIService = comfyUIService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        管理 ComfyUI 工作流文件（API 格式）。支持两个操作：\
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
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments args;
        try {
            args = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析失败: " + e.getMessage());
        }
        if (args == null || !StringUtils.hasText(args.getAction())) {
            throw new ToolExecutor.ToolExecuteException("参数 action 不能为空，可选值: list, detail");
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();
        String action = args.getAction().trim().toLowerCase();

        String result = switch (action) {
            case "list" -> comfyUIService.listWorkflows(sessionId);
            case "detail" -> {
                if (!StringUtils.hasText(args.getWorkflowName())) {
                    throw new ToolExecutor.ToolExecuteException("参数 workflowName 不能为空（action=detail 时必填）");
                }
                yield comfyUIService.workflowDetail(args.getWorkflowName(), sessionId);
            }
            default -> throw new ToolExecutor.ToolExecuteException(
                    "不支持的操作: " + action + "，可选值: list, detail");
        };
        return new ToolExecutor.ToolExecuteResponse(NAME, result);
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
        private String action;
        private String workflowName;
    }
}
