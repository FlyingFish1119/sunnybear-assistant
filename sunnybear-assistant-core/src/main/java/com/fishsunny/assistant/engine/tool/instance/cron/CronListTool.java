package com.fishsunny.assistant.engine.tool.instance.cron;

/*
 * @Usage 定时任务列表工具
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 定时任务列表工具
 * 列出所有已配置的定时任务，按创建时间倒序。
 */
@ToolKitComponent(CronToolKit.class)
@ConditionalOnExpression("${engine.tool.cron.enable:true} && ${engine.tool.cron.list.enable:true}")
public class CronListTool implements ToolHandler {

    public static final String NAME = "cron_list_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CronJobService cronJobService;

    public CronListTool(ObjectMapper objectMapper, CronJobService cronJobService) {
        this.objectMapper = objectMapper;
        this.cronJobService = cronJobService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("列出所有已配置的定时任务，按创建时间倒序排列。返回每条任务的 ID、标题、描述、cron 表达式、触发消息和时间信息。")
                .setRequired(List.of())
                .setParameters(List.of());
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            List<CronJob> jobs = cronJobService.listAll();

            if (jobs.isEmpty()) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "当前没有任何定时任务。\n\n你可以使用 cron_create_tool 创建定时任务。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("定时任务列表（共 **").append(jobs.size()).append("** 个）\n");

            for (int i = 0; i < jobs.size(); i++) {
                CronJob job = jobs.get(i);
                sb.append("\n---\n\n");
                sb.append("**").append(i + 1).append("**. **").append(job.getTitle()).append("**\n");
                sb.append("- ID: `").append(job.getId()).append("`\n");
                if (job.getDescription() != null && !job.getDescription().isEmpty()) {
                    sb.append("- 描述: ").append(job.getDescription()).append("\n");
                }
                sb.append("- cron: `").append(job.getCron()).append("`\n");
                sb.append("- 消息: ").append(job.getMessage()).append("\n");
                sb.append("- 高级模型: ").append(job.getEnablePro() != null && job.getEnablePro() ? "是" : "否").append("\n");
                sb.append("- 创建时间: ").append(job.getCreateTime() != null
                        ? job.getCreateTime().format(FORMATTER) : "未知").append("\n");
                sb.append("- 更新时间: ").append(job.getUpdateTime() != null
                        ? job.getUpdateTime().format(FORMATTER) : "未知").append("\n");
            }

            sb.append("\n---\n\n");
            sb.append("💡 使用 cron_update_tool 修改任务，使用 cron_delete_tool 删除任务。");

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查询定时任务列表失败: " + e.getMessage());
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
}
