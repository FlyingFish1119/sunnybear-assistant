package com.fishsunny.assistant.engine.tool.instance.cron;

/*
 * @Usage 定时任务更新工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.CronToolKit;
import com.fishsunny.assistant.mvc.service.CronJobService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 定时任务更新工具
 * 根据 ID 更新已有定时任务的 cron 表达式或消息内容。
 */
@ToolKitComponent(CronToolKit.class)
@ConditionalOnExpression("${engine.tool.cron.enable:true} && ${engine.tool.cron.update.enable:true}")
public class CronUpdateTool implements ToolHandler {

    public static final String NAME = "cron_update_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CronJobService cronJobService;

    public CronUpdateTool(ObjectMapper objectMapper, CronJobService cronJobService) {
        this.objectMapper = objectMapper;
        this.cronJobService = cronJobService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 ID 更新一个已有的定时任务。可修改标题、描述、cron 表达式和触发消息。仅修改传入的字段，未传入的字段保持不变。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要更新的定时任务 ID。可从 cron_list_tool 获取"),
                        new ToolRegister.Parameters("title", "string", "新的标题（可选，不传则不修改）"),
                        new ToolRegister.Parameters("description", "string", "新的描述（可选，不传则不修改）"),
                        new ToolRegister.Parameters("cron", "string", "新的 cron 表达式（可选，不传则不修改）"),
                        new ToolRegister.Parameters("message", "string", "新的触发消息（可选，不传则不修改）"),
                        new ToolRegister.Parameters("enable_pro", "boolean", "是否启用高级模型（可选，不传则不修改）")
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

        if (arguments.getId() == null) {
            throw new ToolExecutor.ToolExecuteException("参数 id 不能为空");
        }

        try {
            CronJob existing = cronJobService.findById(arguments.getId());
            if (existing == null) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "未找到 ID 为 " + arguments.getId() + " 的定时任务");
            }

            String title = StringUtils.hasText(arguments.getTitle()) ? arguments.getTitle().trim() : existing.getTitle();
            String description = arguments.getDescription() != null ? arguments.getDescription().trim() : existing.getDescription();
            String cron = StringUtils.hasText(arguments.getCron()) ? arguments.getCron().trim() : existing.getCron();
            String message = StringUtils.hasText(arguments.getMessage()) ? arguments.getMessage() : existing.getMessage();
            boolean enablePro = arguments.getEnablePro() != null ? arguments.getEnablePro() : (existing.getEnablePro() != null && existing.getEnablePro());

            CronJob saved = cronJobService.update(arguments.getId(), title, description, cron, message, enablePro);

            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("定时任务更新成功:\n  ID: %s\n  标题: %s\n  描述: %s\n  cron: %s\n  消息: %s\n  高级模型: %s",
                            saved.getId(), saved.getTitle(), saved.getDescription(),
                            saved.getCron(), saved.getMessage(), saved.getEnablePro() ? "是" : "否"));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("定时任务更新失败: " + e.getMessage());
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
        private Integer id;
        private String title;
        private String description;
        private String cron;
        private String message;
        private Boolean enablePro;
    }
}
