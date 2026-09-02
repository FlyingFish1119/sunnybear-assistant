package com.fishsunny.assistant.engine.tool.instance.cron;

/*
 * @Usage 定时任务删除工具
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

import java.util.List;
import java.util.Map;

/**
 * 定时任务删除工具
 * 根据 ID 删除一条定时任务。删除不可恢复。
 */
@ToolKitComponent(CronToolKit.class)
@ConditionalOnExpression("${engine.tool.cron.enable:true} && ${engine.tool.cron.delete.enable:true}")
public class CronDeleteTool implements ToolHandler {

    public static final String NAME = "cron_delete_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CronJobService cronJobService;

    public CronDeleteTool(ObjectMapper objectMapper, CronJobService cronJobService) {
        this.objectMapper = objectMapper;
        this.cronJobService = cronJobService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 ID 删除一条定时任务。删除不可恢复，请先确认 ID 正确。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要删除的定时任务 ID。操作不可逆，请仔细核对。可从 cron_list_tool 获取")
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
            CronJob deleted = cronJobService.delete(arguments.getId());
            if (deleted == null) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "未找到 ID 为 " + arguments.getId() + " 的定时任务，可能已被删除");
            }
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("定时任务删除成功:\n  ID: %s\n  原标题: %s\n  原 cron: %s\n  原消息: %s",
                            deleted.getId(), deleted.getTitle(), deleted.getCron(), deleted.getMessage()));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("定时任务删除失败: " + e.getMessage());
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
    }
}
