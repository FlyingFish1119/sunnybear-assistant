package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 鼠标移动工具，支持平滑移动
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 02:16
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BotToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Map;

@ToolKitComponent(BotToolKit.class)
@ConditionalOnExpression("${engine.tool.bot.enable:true} && ${engine.tool.bot.mouse-move.enable:true}")
public class MouseMoveTool implements ToolHandler {

    public static final String NAME = "mouse_move_tool";

    /** 默认平滑移动时长（毫秒） */
    private static final int DEFAULT_DURATION_MS = 500;
    /** 每步间隔（毫秒），约 100fps */
    private static final int STEP_INTERVAL_MS = 10;
    /** 最小步数，避免步数过少导致移动不够平滑 */
    private static final int MIN_STEPS = 30;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public MouseMoveTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        String description = "将鼠标移动到指定目标坐标位置。"
                + " 当前屏幕尺寸：" + screenSize.width + "x" + screenSize.height + "（宽x高，单位像素）。";

        register = new ToolRegister()
                .setName(NAME)
                .setDescription(description)
                .setRequired(List.of("x", "y"));

        ToolRegister.Parameters xParam = new ToolRegister.Parameters()
                .setParameterName("x")
                .setType("integer")
                .setDescription("目标位置的 X 坐标（像素），相对于屏幕左上角");

        ToolRegister.Parameters yParam = new ToolRegister.Parameters()
                .setParameterName("y")
                .setType("integer")
                .setDescription("目标位置的 Y 坐标（像素），相对于屏幕左上角");

        ToolRegister.Parameters durationParam = new ToolRegister.Parameters()
                .setParameterName("duration")
                .setType("integer")
                .setDescription("（可选）移动时长（毫秒），默认 " + DEFAULT_DURATION_MS + "ms。值越大移动越慢");

        ToolRegister.Parameters dragParam = new ToolRegister.Parameters()
                .setParameterName("drag")
                .setType("boolean")
                .setDescription("（可选）是否为拖拽操作，默认为 false。设置为 true 时，鼠标将保持按下状态，并在移动结束后松开");

        register.setParameters(List.of(xParam, yParam, durationParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getX()) || !StringUtils.hasText(arguments.getY())) {
                throw new ToolExecutor.ToolExecuteException("参数 x 和 y 不能为空");
            }

            int targetX = Integer.parseInt(arguments.getX());
            int targetY = Integer.parseInt(arguments.getY());
            int duration = DEFAULT_DURATION_MS;
            if (StringUtils.hasText(arguments.getDuration())) {
                duration = Integer.parseInt(arguments.getDuration());
                if (duration < 0) {
                    throw new ToolExecutor.ToolExecuteException("duration 不能为负数");
                }
            }

            Robot robot = new Robot();

            // 获取当前鼠标位置
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            if (pointerInfo == null) {
                throw new ToolExecutor.ToolExecuteException("无法获取当前鼠标位置，可能运行在无头环境中");
            }
            Point currentPos = pointerInfo.getLocation();
            int startX = currentPos.x;
            int startY = currentPos.y;

            // 如果拖动， 按下鼠标
            if (arguments.getDrag()) {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            }

            // 如果 duration 为 0，直接移动到目标位置
            if (duration == 0) {
                robot.mouseMove(targetX, targetY);
                return new ToolExecutor.ToolExecuteResponse(
                        name(),
                        "鼠标已移动到 (" + targetX + ", " + targetY + ")"
                );
            }

            // 计算步数：基于步间隔和总时长，确保不少于最小步数
            int steps = Math.max(duration / STEP_INTERVAL_MS, MIN_STEPS);
            int stepDelay = duration / steps;

            // 平滑移动：线性插值
            for (int i = 0; i <= steps; i++) {
                double progress = (double) i / steps;
                int currentX = startX + (int) Math.round((targetX - startX) * progress);
                int currentY = startY + (int) Math.round((targetY - startY) * progress);
                robot.mouseMove(currentX, currentY);

                if (i < steps) {
                    Thread.sleep(stepDelay);
                }
            }

            // 确保最终位置精确
            robot.mouseMove(targetX, targetY);

            // 如果拖动，释放鼠标
            if (arguments.getDrag()) {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }

            return new ToolExecutor.ToolExecuteResponse(
                    name(),
                    "鼠标已从 (" + startX + ", " + startY + ") 平滑移动到 (" + targetX + ", " + targetY + ")，耗时 " + duration + "ms"
            );
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
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
        private String x;
        private String y;
        private String duration;
        private Boolean drag;
    }
}
