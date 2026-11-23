package com.fishsunny.assistant.plug.comfyui.tool;

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

/**
 * 查询 ComfyUI 可用资源：checkpoint 模型、LoRA、VAE、采样器、调度器，以及按类别分组的全部模型文件。
 * <p>
 * 数据来源与老代理一致：卫星机脚本请求 {@code /object_info} 后裁剪成摘要，只把结果回传。
 * </p>
 */
@ToolKitComponent(ComfyUIToolKit.class)
@ConditionalOnExpression("${plug.comfyui.tool.enable:true} && ${plug.comfyui.tool.resources.enable:true}")
public class ComfyUIResourcesTool implements ToolHandler {

    public static final String NAME = "comfyui_resources";

    private final ToolRegister register;
    private final ComfyUIService comfyUIService;

    public ComfyUIResourcesTool(ComfyUIService comfyUIService) {
        this.comfyUIService = comfyUIService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        获取 ComfyUI 当前可用的所有资源。返回 JSON 包含：\
                        checkpoints（模型）、loras（LoRA）、vaes（VAE）、\
                        samplers（采样器）、schedulers（调度器），以及 \
                        models（所有模型文件按类别分组，含 diffusion_models、text_encoders、\
                        controlnet、clip_vision、upscale_models、style_models 等）。\
                        生图前务必调用此工具确认可用模型名称。""")
                .setRequired(List.of());
        register.setParameters(List.of());
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();
        return new ToolExecutor.ToolExecuteResponse(NAME, comfyUIService.resources(sessionId));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }
}
