package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 流程测试工具 - 接受一个字符串，工具流程成功后拼接返回
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.flow-test.enable:true}")
public class FlowTestTool implements ToolHandler {

    public static final String NAME = "flow_test_tool";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FlowTestTool(ObjectMapper objectMapper) {
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("测试工具：接受一个字符串并拼接返回。用于调试工具流程。")
                .setRequired(List.of("input"));
        ToolRegister.Parameters parameter = new ToolRegister.Parameters()
                .setParameterName("input")
                .setType("string")
                .setDescription("需要测试的输入字符串，工具流程成功后会拼接该字符串返回");

        register.setParameters(List.of(parameter));

        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            LocalDateTime startTime = LocalDateTime.now();
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            Thread.sleep(2000);
            if (!StringUtils.hasText(arguments.getInput())) {
                throw new ToolExecutor.ToolExecuteException("参数 input 不能为空");
            }
            // 工具流程成功，拼接输入的字符串
            String result = """
                    ```test
                    工具[flow_test_tool]调用**成功**。
                    [开启时间]：${startTime}
                    [结束时间]：${endTime}
                    [得到参数]：${argument}
                    ```
                    """.replace("${startTime}", startTime.format(DATE_TIME_FORMATTER))
                    .replace("${endTime}", LocalDateTime.now().format(DATE_TIME_FORMATTER))
                    .replace("${argument}", arguments.getInput());
            return new ToolExecutor.ToolExecuteResponse(name(), result);
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
    private static class Arguments {
        private String input;
    }
}
