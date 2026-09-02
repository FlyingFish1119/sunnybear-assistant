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
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.launch_app.enable:true}")
public class AndroidLaunchAppTool implements ToolHandler {

    public static final String NAME = "android_launch_app";
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidLaunchAppTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("启动指定包名的应用。常见包名示例：微信=com.tencent.mm，设置=com.android.settings。")
                .setRequired(List.of("packageName"))
                .setParameters(List.of(
                        param("packageName", "string", "应用包名，如 com.tencent.mm")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new ObjectMapper().readValue(argumentsJson, Map.class);
            String packageName = (String) args.get("packageName");
            if (packageName == null || packageName.isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供要启动的应用包名（packageName）");
            }
            String paramsJson = "{\"packageName\":\"" + packageName.replace("\"", "\\\"") + "\"}";
            String result = bridgeService.sendCommand("launch_app", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("启动应用失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }
}
