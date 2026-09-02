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

@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.generate.enable:true}")
public class ComfyUIGenerateTool implements ToolHandler {

    public static final String NAME = "comfyui_generate";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final ComfyUIBridgeService bridgeService;

    public ComfyUIGenerateTool(ObjectMapper objectMapper, ComfyUIBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("提交 ComfyUI workflow JSON 并执行生图。支持轮询等待完成，返回生成结果（含图片信息）。")
                .setRequired(List.of("workflow"));

        register.setParameters(List.of(
                param("workflow", "string", "ComfyUI workflow JSON 对象。包含所有节点定义和连接关系。"),
                param("timeout", "integer", "超时秒数，默认 1800 秒（30 分钟）。复杂 workflow 建议设大一些。")
        ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(args.getWorkflow())) {
                throw new ToolExecutor.ToolExecuteException("参数 workflow 不能为空");
            }
            String paramsJson = objectMapper.writeValueAsString(args);
            String result = bridgeService.sendCommand("generate", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("ComfyUI 生图失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data @Accessors(chain = true)
    private static class Arguments {
        private String workflow;
        private Integer timeout;
    }
}
