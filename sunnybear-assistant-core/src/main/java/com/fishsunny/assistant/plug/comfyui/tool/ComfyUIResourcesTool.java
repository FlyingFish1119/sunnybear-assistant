package com.fishsunny.assistant.plug.comfyui.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

/**
 * 查询 ComfyUI 可用资源：checkpoint 模型、LoRA、VAE、采样器、调度器。
 * 返回结构化的 {@code ResourceInfo} JSON。
 */
@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.resources.enable:true}")
public class ComfyUIResourcesTool implements ToolHandler {

    public static final String NAME = "comfyui_resources";

    private final ToolRegister register;
    private final ComfyUIBridgeService bridgeService;

    public ComfyUIResourcesTool(ComfyUIBridgeService bridgeService) {
        this.bridgeService = bridgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        获取 ComfyUI 当前可用的所有资源。返回 JSON 包含：\
                        checkpoints（模型列表）、loRAs（LoRA 列表）、\
                        VAEs（VAE 列表）、samplers（采样器）、schedulers（调度器）。\
                        生图前务必调用此工具确认可用模型名称。""")
                .setRequired(List.of());
        register.setParameters(List.of());
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            String result = bridgeService.sendCommand("resources", "{}");
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查询资源失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }
}
