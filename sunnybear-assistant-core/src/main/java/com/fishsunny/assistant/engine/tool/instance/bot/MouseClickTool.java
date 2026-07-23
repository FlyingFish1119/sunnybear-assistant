package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 鼠标点击工具，支持左键/右键/中键(滚轮)点击，支持设置点击次数
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 02:30
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.instance.BotToolKit;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Map;

@ToolKitComponent(BotToolKit.class)
@ConditionalOnExpression("${engine.tool.bot.enable:true} && ${engine.tool.bot.mouse-click.enable:true}")
public class MouseClickTool implements ToolHandler {

    public static final String NAME = "mouse_click_tool";

    public static final String BUTTON_LEFT = "left";
    public static final String BUTTON_RIGHT = "right";
    public static final String BUTTON_MIDDLE = "middle";

    /** 两次点击之间的默认间隔（毫秒） */
    private static final int DEFAULT_CLICK_INTERVAL_MS = 80;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public MouseClickTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("鼠标点击工具，在当前鼠标位置执行点击操作。支持左键(click)、右键(context menu)、中键/滚轮键(middle click)点击，支持设置点击次数（如双击）。")
                .setRequired(List.of("button"));

        ToolRegister.Parameters buttonParam = new ToolRegister.Parameters()
                .setParameterName("button")
                .setType("string")
                .setDescription("点击的鼠标按键，可选值：left（左键）、right（右键）、middle（中键/滚轮键）");

        ToolRegister.Parameters countParam = new ToolRegister.Parameters()
                .setParameterName("count")
                .setType("integer")
                .setDescription("（可选）点击次数，默认 1。设置为 2 即为双击，设置为 3 即为三连击");

        register.setParameters(List.of(buttonParam, countParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getButton())) {
                throw new ToolExecutor.ToolExecuteException("参数 button 不能为空");
            }

            String button = arguments.getButton().toLowerCase().trim();
            int buttonMask = resolveButtonMask(button);

            int count = 1;
            if (StringUtils.hasText(arguments.getCount())) {
                count = Integer.parseInt(arguments.getCount());
                if (count < 1) {
                    throw new ToolExecutor.ToolExecuteException("点击次数 count 必须大于 0");
                }
                if (count > 10) {
                    throw new ToolExecutor.ToolExecuteException("点击次数 count 不能超过 10 次");
                }
            }

            // 获取当前鼠标位置用于日志
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            Point currentPos = pointerInfo != null ? pointerInfo.getLocation() : new Point(-1, -1);

            Robot robot = new Robot();

            // 执行点击
            for (int i = 0; i < count; i++) {
                robot.mousePress(buttonMask);
                robot.mouseRelease(buttonMask);

                // 多次点击之间添加间隔
                if (i < count - 1) {
                    Thread.sleep(DEFAULT_CLICK_INTERVAL_MS);
                }
            }

            String buttonName = getButtonDisplayName(button);
            String countDesc = count > 1 ? count + "次连击" : "单击";
            return new ToolExecutor.ToolExecuteResponse(
                    name(),
                    "已在 (" + currentPos.x + ", " + currentPos.y + ") 处执行 " + buttonName + " " + countDesc
            );
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 将按钮名称解析为 AWT InputEvent 掩码
     */
    private int resolveButtonMask(String button) throws ToolExecutor.ToolExecuteException {
        switch (button) {
            case BUTTON_LEFT:
                return InputEvent.BUTTON1_DOWN_MASK;
            case BUTTON_RIGHT:
                return InputEvent.BUTTON3_DOWN_MASK;
            case BUTTON_MIDDLE:
                return InputEvent.BUTTON2_DOWN_MASK;
            default:
                throw new ToolExecutor.ToolExecuteException(
                        "不支持的鼠标按键 [" + button + "]，可选值：left（左键）、right（右键）、middle（中键）"
                );
        }
    }

    private String getButtonDisplayName(String button) {
        return switch (button) {
            case BUTTON_LEFT -> "左键";
            case BUTTON_RIGHT -> "右键";
            case BUTTON_MIDDLE -> "中键（滚轮键）";
            default -> button;
        };
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    public static class Arguments {
        private String button;
        private String count;
    }
}
