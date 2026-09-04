package com.fishsunny.assistant.engine.tool.instance.cron;

/*
 * @Usage 定时任务创建工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.CronToolKit;
import com.fishsunny.assistant.mvc.service.CronJobService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 定时任务创建工具
 * 创建一个新的定时任务，指定 cron 表达式和触发时的消息内容。
 */
@ToolKitComponent(CronToolKit.class)
@ConditionalOnExpression("${engine.tool.cron.enable:true} && ${engine.tool.cron.create.enable:true}")
public class CronCreateTool implements ToolHandler {

    public static final String NAME = "cron_create_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CronJobService cronJobService;

    public CronCreateTool(ObjectMapper objectMapper, CronJobService cronJobService) {
        this.objectMapper = objectMapper;
        this.cronJobService = cronJobService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("创建一个新的定时任务。需要指定标题、描述、cron 表达式和触发时要发送的消息内容。cron 格式为 5 字段：分 时 日 月 周（如 '0 9 * * *' 表示每天 9 点）。")
                .setRequired(List.of("title", "cron", "message"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string", "定时任务标题，简洁明确。如'每日早报'"),
                        new ToolRegister.Parameters("description", "string", "任务描述，说明这个定时任务的用途（可选）"),
                        new ToolRegister.Parameters("cron", "string", "cron 表达式，6 字段空格分隔：秒(0-59) 分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-7)。如 '0 0 9 * * *' 表示每天上午 9:00，'0 */5 * * * *' 表示每 5 分钟"),
                        new ToolRegister.Parameters("message", "string", "定时触发时要发送给 AI 的消息内容，注意你应该以用户的口吻生成这条消息"),
                        new ToolRegister.Parameters("enable_pro", "boolean", "是否启用高级模型（默认 false）。对于需要复杂推理的任务可设为 true")
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

        if (!StringUtils.hasText(arguments.getTitle())) {
            throw new ToolExecutor.ToolExecuteException("参数 title 不能为空");
        }
        if (!StringUtils.hasText(arguments.getCron())) {
            throw new ToolExecutor.ToolExecuteException("参数 cron 不能为空");
        }
        if (!StringUtils.hasText(arguments.getMessage())) {
            throw new ToolExecutor.ToolExecuteException("参数 message 不能为空");
        }

        boolean enablePro = arguments.getEnablePro() != null && arguments.getEnablePro();

        try {
            CronJob saved = cronJobService.create(
                    arguments.getTitle().trim(),
                    arguments.getDescription() != null ? arguments.getDescription().trim() : "",
                    arguments.getCron().trim(),
                    arguments.getMessage(),
                    enablePro,
                    false); // 无审查开关仅由用户在设置页显式开启，AI 不可通过工具开启

            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("定时任务创建成功:\n  ID: %s\n  标题: %s\n  描述: %s\n  cron: %s\n  消息: %s\n  高级模型: %s",
                            saved.getId(), saved.getTitle(), saved.getDescription(),
                            saved.getCron(), saved.getMessage(), saved.getEnablePro() ? "是" : "否"));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("定时任务创建失败: " + e.getMessage());
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
        private String title;
        private String description;
        private String cron;
        private String message;
        private Boolean enablePro;
    }
}
