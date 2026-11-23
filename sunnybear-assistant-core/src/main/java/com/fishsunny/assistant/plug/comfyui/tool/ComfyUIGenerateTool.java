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
 * 提交 ComfyUI workflow 生图。
 * <p>
 * 提交、轮询、取结果全部由卫星机上的 Python 脚本完成；生成图随后经卫星下载通道拉回会话文件，
 * 返回 JSON 里的 {@code files} 字段即会话内可移植引用，上层可直接读取图片。
 * </p>
 */
@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.generate.enable:true}")
public class ComfyUIGenerateTool implements ToolHandler {

    public static final String NAME = "comfyui_generate";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final ComfyUIService comfyUIService;

    public ComfyUIGenerateTool(ObjectMapper objectMapper, ComfyUIService comfyUIService) {
        this.objectMapper = objectMapper;
        this.comfyUIService = comfyUIService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("提交 ComfyUI workflow JSON（API 格式）并执行生图。阻塞等待完成，"
                        + "返回中的 images 为远端输出文件信息、files 为已拉回本会话的文件引用。")
                .setRequired(List.of("workflow"));

        register.setParameters(List.of(
                param("workflow", "string", "ComfyUI workflow JSON 对象（API 格式，节点 id → {class_type, inputs}）。"),
                param("timeout", "integer", "超时秒数，默认取 plug.comfyui.generate-timeout-s（1800 秒）。复杂 workflow 建议设大一些。")
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
        if (args == null || !StringUtils.hasText(args.getWorkflow())) {
            throw new ToolExecutor.ToolExecuteException("参数 workflow 不能为空");
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();

        String result = comfyUIService.generate(args.getWorkflow(), args.getTimeout(), sessionId);
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

    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data
    private static class Arguments {
        private String workflow;
        private Integer timeout;
    }
}
