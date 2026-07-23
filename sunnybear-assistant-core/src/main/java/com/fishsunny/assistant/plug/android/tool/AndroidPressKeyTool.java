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

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidPressKeyTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("在 Android 设备上模拟系统按键。支持: " + String.join(", ", SUPPORTED_KEYS) + "。")
                .setRequired(List.of("key"))
                .setParameters(List.of(
                        param("deviceId", "string", "目标设备 ID。不填则使用第一个已连接设备。"),
                        param("key", "string", "按键类型。可选: back(返回), home(主页), recent(最近任务), "
                                + "notification(通知栏), power_dialog(电源菜单), screenshot(系统截图), "
                                + "quick_settings(快捷设置), lock_screen(锁屏)")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args.getKey() == null || args.getKey().isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供按键类型。支持: " + String.join(", ", SUPPORTED_KEYS));
            }
            String deviceId = resolveDeviceId(args.getDeviceId());
            String paramsJson = "{\"key\":\"" + args.getKey().replace("\"", "\\\"") + "\"}";
            String result = bridgeService.sendCommand(deviceId, "press_key", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("按键失败: " + e.getMessage());
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
        private String key;
    }
}
