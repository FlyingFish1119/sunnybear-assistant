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
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.wait.enable:true}")
public class AndroidWaitTool implements ToolHandler {

    public static final String NAME = "android_wait";
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidWaitTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("等待指定文本出现在屏幕上。用于等待页面加载、弹窗出现等场景。")
                .setRequired(List.of("text"))
                .setParameters(List.of(
                        param("deviceId", "string", "目标设备 ID。不填则使用第一个已连接设备。"),
                        param("text", "string", "要等待出现的文本（模糊匹配）"),
                        param("timeout", "integer", "超时时间（毫秒），默认 5000。最大建议不超过 30000。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args.getText() == null || args.getText().isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("请提供要等待的文本（text）");
            }
            String deviceId = resolveDeviceId(args.getDeviceId());
            int timeout = args.getTimeout() != null ? args.getTimeout() : 5000;
            String paramsJson = "{\"text\":\"" + args.getText().replace("\"", "\\\"")
                    + "\",\"timeout\":" + timeout + "}";
            String result = bridgeService.sendCommand(deviceId, "wait_for_text", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("等待文本失败: " + e.getMessage());
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
        private Integer timeout;
    }
}
