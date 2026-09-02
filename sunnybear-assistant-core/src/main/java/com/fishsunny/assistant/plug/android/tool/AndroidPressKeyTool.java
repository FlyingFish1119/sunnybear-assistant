package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.press_key.enable:true}")
public class AndroidPressKeyTool implements ToolHandler {

    public static final String NAME = "android_press_key";
    private static final List<String> SUPPORTED_KEYS = Arrays.asList(
            "back", "home", "recent", "notification", "power_dialog", "screenshot",
            "quick_settings", "lock_screen");

    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidPressKeyTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("在 Android 设备上模拟系统按键。支持: " + String.join(", ", SUPPORTED_KEYS) + "。")
                .setRequired(List.of("key"))
                .setParameters(List.of(
                        param("key", "string", "按键类型。可选: back(返回), home(主页), recent(最近任务), "
                                + "notification(通知栏), power_dialog(电源菜单), screenshot(系统截图), "
                                + "quick_settings(快捷设置), lock_screen(锁屏)")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new ObjectMapper().readValue(argumentsJson, Map.class);
            String key = (String) args.get("key");
            if (key == null || key.isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供按键类型。支持: " + String.join(", ", SUPPORTED_KEYS));
            }
            String paramsJson = "{\"key\":\"" + key.replace("\"", "\\\"") + "\"}";
            String result = bridgeService.sendCommand("press_key", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("按键失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }
}
