package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.wait.enable:true}")
public class AndroidWaitTool implements ToolHandler {

    public static final String NAME = "android_wait";
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidWaitTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("等待指定文本出现在屏幕上。用于等待页面加载、弹窗出现等场景。")
                .setRequired(List.of("text"))
                .setParameters(List.of(
                        param("text", "string", "要等待出现的文本（模糊匹配）"),
                        param("timeout", "integer", "超时时间（毫秒），默认 5000。最大建议不超过 30000。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new ObjectMapper().readValue(argumentsJson, Map.class);
            String text = (String) args.get("text");
            if (text == null || text.isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供要等待的文本（text）");
            }
            Integer timeout = args.get("timeout") instanceof Number n ? n.intValue() : 5000;
            String paramsJson = "{\"text\":\"" + text.replace("\"", "\\\"")
                    + "\",\"timeout\":" + timeout + "}";
            String result = bridgeService.sendCommand("wait_for_text", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("等待文本失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }
}
