package com.fishsunny.assistant.engine.tool.instance.calc;

/*
 * @Usage 计算工具 - 用于执行基础数学运算，如加减乘除、三角函数、对数等
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 03:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.CalculationToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import lombok.Data;
import org.springframework.util.StringUtils;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.List;
import java.util.Map;

@ToolKitComponent(CalculationToolKit.class)
@ConditionalOnExpression("${engine.tool.calc.enable:true} && ${engine.tool.calc.calculate.enable:true}")
public class CalculateTool implements ToolHandler {

    public static final String NAME = "calculate_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public CalculateTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("计算数学表达式。支持加减乘除、三角函数、对数、幂运算等 JavaScript Math 标准库函数。示例：1+2*3、Math.sqrt(16)、Math.pow(2, 8)。")
                .setRequired(List.of("expression"));

        ToolRegister.Parameters expressionParam = new ToolRegister.Parameters()
                .setParameterName("expression")
                .setType("string")
                .setDescription("需要计算的数学表达式，例如：1+2*3、(5+3)/2、Math.sin(Math.PI/2)、Math.sqrt(16)、Math.pow(2, 8) 等");

        register.setParameters(List.of(expressionParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null || !StringUtils.hasText(arguments.getExpression())) {
                throw new ToolExecutor.ToolExecuteException("参数 expression 不能为空");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        String expression = arguments.getExpression().trim();

        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            if (engine == null) {
                throw new ToolExecutor.ToolExecuteException("JavaScript 脚本引擎不可用，无法执行计算");
            }

            Object result = engine.eval(expression);

            String resultStr;
            if (result == null) {
                resultStr = "null";
            } else if (result instanceof Double) {
                double d = (Double) result;
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    resultStr = String.valueOf((long) d);
                } else {
                    resultStr = result.toString();
                }
            } else {
                resultStr = result.toString();
            }

            return new ToolExecutor.ToolExecuteResponse(name(), expression + " = " + resultStr);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("计算表达式 [" + expression + "] 时出错: " + e.getMessage());
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
    private static class Arguments {
        private String expression;
    }
}
