package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 等待流程工具 - 接受等待时间（秒），等待指定时间后返回，让AI拥有真正的等待概念
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.wait-flow.enable:true}")
public class WaitFlowTool implements ToolHandler {

    public static final String NAME = "wait_flow_tool";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public WaitFlowTool(ObjectMapper objectMapper) {
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("等待指定秒数后返回。")
                .setRequired(List.of("seconds"));
        ToolRegister.Parameters parameter = new ToolRegister.Parameters()
                .setParameterName("seconds")
                .setType("integer")
                .setDescription("需要等待的时间，单位：秒。工具会阻塞等待这段时间后返回结果。");

        register.setParameters(List.of(parameter));

        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            LocalDateTime startTime = LocalDateTime.now();
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            int seconds = arguments.getSeconds();
            if (seconds <= 0) {
                throw new ToolExecutor.ToolExecuteException("参数 seconds 必须大于 0");
            }

            // 等待指定时间
            Thread.sleep(seconds * 1000L);

            String result = """
                    ```flow
                    工具[wait_flow_tool]等待**完成**。
                    [等待时长]：${seconds} 秒
                    [开始时间]：${startTime}
                    [结束时间]：${endTime}
                    ```
                    """.replace("${seconds}", String.valueOf(seconds))
                    .replace("${startTime}", startTime.format(DATE_TIME_FORMATTER))
                    .replace("${endTime}", LocalDateTime.now().format(DATE_TIME_FORMATTER));
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
        private int seconds;
    }
}
