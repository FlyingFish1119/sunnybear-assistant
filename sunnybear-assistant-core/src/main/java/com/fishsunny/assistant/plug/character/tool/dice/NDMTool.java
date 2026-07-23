package com.fishsunny.assistant.plug.character.tool.dice;

/*
 * @Usage NDM 骰子工具 —— 投掷 n 个 m 面骰子，返回每个骰子的结果和总和
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@ToolKitComponent(DiceToolKit.class)
@ConditionalOnExpression("${plug.character.tool.dice.enable:false} && ${plug.character.tool.dice.ndm.enable:true}")
public class NDMTool implements ToolHandler {

    public static final String NAME = "ndm_roll";

    private static final Logger log = LoggerFactory.getLogger(NDMTool.class);
    private static final int MAX_COUNT = 100;
    private static final int MAX_SIDES = 1000;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public NDMTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        投 n 个 m 面骰子，返回各骰子结果和总和。适用于伤害掷骰、属性生成等场景。""")
                .setRequired(List.of("count", "sides"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("count", "integer",
                                "骰子数量（n），范围 1-" + MAX_COUNT + "。例如 3d6 中的 3。"),
                        new ToolRegister.Parameters("sides", "integer",
                                "骰子面数（m），范围 2-" + MAX_SIDES + "。例如 3d6 中的 6（六面骰），1d20 中的 20（二十面骰）。"),
                        new ToolRegister.Parameters("bonus", "integer",
                                "固定加值（可选），加到骰子总和上。可为正数、负数或零。例如 3d6+2 则填 2，2d8-1 则填 -1。不填默认为 0。"),
                        new ToolRegister.Parameters("reason", "string",
                                "投掷原因（可选），说明为什么掷骰子。例如：「火球术伤害」「力量属性生成」「先攻检定」。不填则仅显示骰子结果。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        // 校验参数
        if (arguments.getCount() == null || arguments.getCount() < 1 || arguments.getCount() > MAX_COUNT) {
            throw new ToolExecutor.ToolExecuteException("骰子数量（count）必须在 1-" + MAX_COUNT + " 之间");
        }
        if (arguments.getSides() == null || arguments.getSides() < 2 || arguments.getSides() > MAX_SIDES) {
            throw new ToolExecutor.ToolExecuteException("骰子面数（sides）必须在 2-" + MAX_SIDES + " 之间");
        }

        int count = arguments.getCount();
        int sides = arguments.getSides();
        int bonus = arguments.getBonus() != null ? arguments.getBonus() : 0;
        String reason = StringUtils.hasText(arguments.getReason()) ? arguments.getReason().trim() : null;

        // ========== 掷骰子 ==========
        int[] rolls = new int[count];
        int sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int i = 0; i < count; i++) {
            rolls[i] = rollDie(sides);
            sum += rolls[i];
            if (rolls[i] < min) min = rolls[i];
            if (rolls[i] > max) max = rolls[i];
        }

        int total = sum + bonus;

        // ========== 构建返回结果 ==========
        StringBuilder result = new StringBuilder();
        result.append("🎲 **").append(count).append("d").append(sides);
        if (bonus != 0) {
            result.append(bonus > 0 ? "+" : "").append(bonus);
        }
        result.append(" 掷骰**");

        if (reason != null) {
            result.append(" —— ").append(reason);
        }
        result.append("\n\n");

        // 单个骰子：简洁展示
        if (count == 1) {
            result.append("- **结果**：").append(rolls[0]);
            if (bonus != 0) {
                result.append("，加值后 = ").append(total);
            }
            result.append("\n");
        } else {
            // 多个骰子：逐个展示 + 统计
            String rollList = java.util.Arrays.stream(rolls)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(", "));
            result.append("- **各骰结果**：[").append(rollList).append("]\n");
            result.append("- **总和**：").append(sum);
            if (bonus != 0) {
                result.append(" + (").append(bonus > 0 ? "+" : "").append(bonus).append(") = ").append(total);
            }
            result.append("\n");
            if (count > 1) {
                result.append("- **最低**：").append(min).append("　**最高**：").append(max).append("\n");
                double avg = (double) sum / count;
                result.append("- **平均**：").append(String.format("%.1f", avg)).append("\n");
            }
        }

        log.debug("NDM 掷骰 {}d{}: sum={}, rolls=[{}]", count, sides, sum,
                java.util.Arrays.stream(rolls).mapToObj(String::valueOf).collect(Collectors.joining(",")));

        return new ToolExecutor.ToolExecuteResponse(NAME, result.toString());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    // ==================== 内部方法 ====================

    /**
     * 投掷一个 sides 面骰子（1-sides）
     */
    private int rollDie(int sides) {
        return ThreadLocalRandom.current().nextInt(1, sides + 1);
    }

    @Data
    private static class Arguments {
        private Integer count;
        private Integer sides;
        private Integer bonus;
        private String reason;
    }
}
