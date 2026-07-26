package com.fishsunny.comfyui.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.comfyui.comfyui.ComfyUIHttpClient;
import com.fishsunny.comfyui.protocol.CommandRequest;

import java.util.Map;

/**
 * 命令分发器。支持 generate / resources / view / workflow-list / workflow-detail。
 */
public class CommandDispatcher {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommandDispatcher.class);

    private final ComfyUIHttpClient comfyUI;
    private final ObjectMapper objectMapper;

    public CommandDispatcher(ComfyUIHttpClient comfyUI) {
        this.comfyUI = comfyUI;
        this.objectMapper = new ObjectMapper();
    }

    public String dispatch(CommandRequest cmd) throws Exception {
        CommandRequest.CommandParams p = cmd.params != null
                ? cmd.params : new CommandRequest.CommandParams();

        return switch (cmd.method) {
            case "generate" -> {
                if (p.workflow == null || p.workflow.isEmpty())
                    yield error("参数 workflow 不能为空");
                int timeout = p.timeout != null ? p.timeout : 120;
                yield toJson(comfyUI.generate(p.workflow, timeout));
            }
            case "resources" -> toJson(comfyUI.getResources());
            case "view" -> {
                if (p.filename == null || p.filename.isEmpty())
                    yield error("参数 filename 不能为空");
                yield toJson(comfyUI.viewImage(p.filename, p.type));
            }
            case "workflow-list" -> toJson(comfyUI.listWorkflows());
            case "workflow-detail" -> {
                if (p.workflowName == null || p.workflowName.isEmpty())
                    yield error("参数 workflowName 不能为空");
                yield toJson(comfyUI.getWorkflowDetail(p.workflowName));
            }
            default -> error("不支持的命令: " + cmd.method + "。支持: generate, resources, view, workflow-list, workflow-detail");
        };
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
