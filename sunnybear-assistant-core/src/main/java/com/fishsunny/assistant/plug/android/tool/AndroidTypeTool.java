package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.type.enable:true}")
public class AndroidTypeTool implements ToolHandler {

    public static final String NAME = "android_type";
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidTypeTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("在当前焦点输入框中输入文本。需先点击输入框获得焦点，或用 targetHint 自动定位输入框。")
                .setRequired(List.of("text"))
                .setParameters(List.of(
                        param("text", "string", "要输入的文本内容"),
                        param("targetHint", "string", "目标输入框的 hint 文本，如\"搜索\"。用于自动定位输入框。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args.getText() == null || args.getText().isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请输入要输入的文本");
            }
            String paramsJson = objectMapper.writeValueAsString(Map.of(
                    "inputText", args.getText(),
                    "targetHint", args.getTargetHint() != null ? args.getTargetHint() : ""
            ));
            String result = bridgeService.sendCommand("type", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 输入失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data
    private static class Arguments {
        private String text;
        private String targetHint;
    }
}
