package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 鼠标滚轮工具，支持滚轮上下滚动
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 02:50
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
import java.util.List;
import java.util.Map;

@ToolKitComponent(BotToolKit.class)
@ConditionalOnExpression("${engine.tool.bot.enable:true} && ${engine.tool.bot.mouse-scroll.enable:true}")
public class MouseScrollTool implements ToolHandler {

    public static final String NAME = "mouse_scroll_tool";

    /** 滚动步间隔（毫秒），模拟自然逐格滚动 */
    private static final int STEP_DELAY_MS = 30;
    /** 最大单次滚动量，防止误操作 */
    private static final int MAX_AMOUNT = 50;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public MouseScrollTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("在当前鼠标位置执行滚轮滚动。正值向下，负值向上。")
                .setRequired(List.of("amount"));

        ToolRegister.Parameters amountParam = new ToolRegister.Parameters()
                .setParameterName("amount")
                .setType("integer")
                .setDescription("滚动量（滚轮刻度），正数向下滚，负数向上滚。常用值：3（向下翻一点）、-3（向上翻一点）、"
                        + "10（向下翻一段）、-10（向上翻一段）。范围 [" + (-MAX_AMOUNT) + ", " + MAX_AMOUNT + "]");

        ToolRegister.Parameters stepDelayParam = new ToolRegister.Parameters()
                .setParameterName("step_delay")
                .setType("integer")
                .setDescription("（可选）每格滚动的间隔时间（毫秒），默认 " + STEP_DELAY_MS + "ms。设为 0 则一次性滚动全部刻度");

        register.setParameters(List.of(amountParam, stepDelayParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getAmount())) {
                throw new ToolExecutor.ToolExecuteException("参数 amount 不能为空");
            }

            int amount = Integer.parseInt(arguments.getAmount());
            if (Math.abs(amount) > MAX_AMOUNT) {
                throw new ToolExecutor.ToolExecuteException(
                        "滚动量不能超过 ±" + MAX_AMOUNT + "，当前值：" + amount);
            }
            if (amount == 0) {
                throw new ToolExecutor.ToolExecuteException("滚动量不能为 0");
            }

            int stepDelay = STEP_DELAY_MS;
            if (StringUtils.hasText(arguments.getStepDelay())) {
                stepDelay = Integer.parseInt(arguments.getStepDelay());
                if (stepDelay < 0) {
                    throw new ToolExecutor.ToolExecuteException("step_delay 不能为负数");
                }
            }

            // 获取当前鼠标位置用于日志
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            Point currentPos = pointerInfo != null ? pointerInfo.getLocation() : new Point(-1, -1);

            Robot robot = new Robot();

            // 逐格滚动，模拟自然滚动效果
            int direction = amount > 0 ? 1 : -1;
            int steps = Math.abs(amount);

            for (int i = 0; i < steps; i++) {
                robot.mouseWheel(direction);

                if (stepDelay > 0 && i < steps - 1) {
                    Thread.sleep(stepDelay);
                }
            }

            String directionDesc = amount > 0 ? "向下" : "向上";
            return new ToolExecutor.ToolExecuteResponse(
                    name(),
                    "已在 (" + currentPos.x + ", " + currentPos.y + ") 处执行滚轮" + directionDesc
                            + "滚动 " + Math.abs(amount) + " 格"
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
        private String amount;
        private String stepDelay;
    }
}
