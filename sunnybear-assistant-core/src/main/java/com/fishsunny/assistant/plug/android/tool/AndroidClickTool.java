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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.click.enable:true}")
public class AndroidClickTool implements ToolHandler {

    public static final String NAME = "android_click";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidClickTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = buildRegister();
    }

    private ToolRegister buildRegister() {
        ToolRegister r = new ToolRegister()
                .setName(NAME)
                .setDescription("点击 Android 设备屏幕上的 UI 元素。可通过坐标 (x, y) 或文本内容定位目标。"
                        + "使用场景：先调用 android_get_ui_tree 获取屏幕元素列表，再根据元素文本或坐标进行点击。"
                        + "例如：点击发送按钮 → text=\"发送\"；点击坐标 → x=500, y=800。")
                .setRequired(List.of());

        List<ToolRegister.Parameters> params = new ArrayList<>();
        params.add(param("deviceId", "string", "目标设备 ID。不填则使用第一个已连接设备。可用 android_list_devices 查询在线设备。"));
        params.add(param("x", "integer", "点击的 X 坐标（屏幕像素）"));
        params.add(param("y", "integer", "点击的 Y 坐标（屏幕像素）"));
        params.add(param("text", "string", "要点击的元素文本（模糊匹配）。APK 会搜索包含此文本的可点击元素并点击其中心。"));

        r.setParameters(params);
        return r;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            if (args.getX() == null && args.getY() == null && !StringUtils.hasText(args.getText())) {
                throw new ToolExecutor.ToolExecuteException("请提供点击坐标 (x, y) 或目标文本 (text)");
            }
            String deviceId = resolveDeviceId(args.getDeviceId());
            String paramsJson = objectMapper.writeValueAsString(args);
            String result = bridgeService.sendCommand(deviceId, "click", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 点击失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }

    private String resolveDeviceId(String deviceId) throws ToolExecutor.ToolExecuteException {
        if (StringUtils.hasText(deviceId)) return deviceId;
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
        private Integer x;
        private Integer y;
        private String text;
    }
}
