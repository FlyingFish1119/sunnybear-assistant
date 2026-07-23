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
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.launch_app.enable:true}")
public class AndroidLaunchAppTool implements ToolHandler {

    public static final String NAME = "android_launch_app";
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidLaunchAppTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("启动指定包名的应用。常见包名示例：微信=com.tencent.mm，设置=com.android.settings。")
                .setRequired(List.of("packageName"))
                .setParameters(List.of(
                        param("deviceId", "string", "目标设备 ID。不填则使用第一个已连接设备。"),
                        param("packageName", "string", "应用包名，如 com.tencent.mm")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args.getPackageName() == null || args.getPackageName().isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供要启动的应用包名（packageName）");
            }
            String deviceId = resolveDeviceId(args.getDeviceId());
            String paramsJson = "{\"packageName\":\"" + args.getPackageName().replace("\"", "\\\"") + "\"}";
            String result = bridgeService.sendCommand(deviceId, "launch_app", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("启动应用失败: " + e.getMessage());
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
        private String packageName;
    }
}
