package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
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
                .setDescription("点击屏幕上指定坐标或文本对应的 UI 元素。推荐先通过 android_get_ui_tree 获取元素信息。")
                .setRequired(List.of());

        List<ToolRegister.Parameters> params = new ArrayList<>();
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
            String paramsJson = objectMapper.writeValueAsString(args);
            String result = bridgeService.sendCommand("click", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 点击失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data @Accessors(chain = true)
    private static class Arguments {
        private Integer x;
        private Integer y;
        private String text;
    }
}
