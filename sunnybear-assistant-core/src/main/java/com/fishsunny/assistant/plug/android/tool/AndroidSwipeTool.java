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

import java.util.List;
import java.util.Map;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.swipe.enable:true}")
public class AndroidSwipeTool implements ToolHandler {

    public static final String NAME = "android_swipe";
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidSwipeTool(ObjectMapper objectMapper, AndroidBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("在 Android 设备屏幕上滑动。可用于滚动列表、切换页面、下拉刷新等。")
                .setRequired(List.of("x1", "y1", "x2", "y2"))
                .setParameters(List.of(
                        param("x1", "integer", "滑动起点 X 坐标"),
                        param("y1", "integer", "滑动起点 Y 坐标"),
                        param("x2", "integer", "滑动终点 X 坐标"),
                        param("y2", "integer", "滑动终点 Y 坐标"),
                        param("duration", "integer", "滑动持续时间（毫秒），默认 300。越大越慢越平滑")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);
            String paramsJson = objectMapper.writeValueAsString(args);
            String result = bridgeService.sendCommand("swipe", paramsJson);
            return new ToolExecutor.ToolExecuteResponse(NAME, result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 滑动失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data @Accessors(chain = true)
    private static class Arguments {
        private Integer x1, y1, x2, y2;
        private Integer duration;
    }
}
