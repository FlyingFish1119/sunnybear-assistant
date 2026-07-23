package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import lombok.Data;
import lombok.experimental.Accessors;
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
                .setDescription("在 Android 设备当前焦点的输入框中输入文本。"
                        + "使用前请先调用 android_click 点击目标输入框使其获得焦点。"
                        + "也可通过 targetHint 参数指定目标输入框的提示文本，APK 会自动查找。")
                .setRequired(List.of("text"))
                .setParameters(List.of(
                        param("deviceId", "string", "目标设备 ID。不填则使用第一个已连接设备。"),
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
            String deviceId = resolveDeviceId(args.getDeviceId());
            String paramsJson = objectMapper.writeValueAsString(Map.of(
                    "inputText", args.getText(),
                    "targetHint", args.getTargetHint() != null ? args.getTargetHint() : ""
            ));
            String result = bridgeService.sendCommand(deviceId, "type", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 输入失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }

    private String resolveDeviceId(String deviceId) throws ToolExecutor.ToolExecuteException {
        if (deviceId != null && !deviceId.isEmpty()) return deviceId;
        String first = bridgeService.getFirstDeviceId();
        if (first == null) throw new ToolExecutor.ToolExecuteException("没有已连接的 Android 设备");
        return first;
    }

    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data @Accessors(chain = true)
    private static class Arguments {
        private String deviceId;
        private String text;
        private String targetHint;
    }
}
