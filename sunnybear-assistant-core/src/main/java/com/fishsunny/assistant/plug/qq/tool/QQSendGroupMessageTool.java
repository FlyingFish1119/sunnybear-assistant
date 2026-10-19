package com.fishsunny.assistant.plug.qq.tool;

/*
 * @Usage QQ 主动群消息发送工具 —— 把通知推到指定群
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/19
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.qq.service.QQBotService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(QQToolKit.class)
@ConditionalOnExpression("${plug.qq.tool.enable:true} && ${plug.qq.tool.send_group_message.enable:true}")
public class QQSendGroupMessageTool implements ToolHandler {

    public static final String NAME = "qq_send_group_message";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final QQBotService qqBotService;

    public QQSendGroupMessageTool(ObjectMapper objectMapper, @Lazy QQBotService qqBotService) {
        this.objectMapper = objectMapper;
        this.qqBotService = qqBotService;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        主动往指定 QQ 群发一条消息，用于把通知、结果推到群里。
                        正文用空行分段，每一段会作为一条独立的消息连着发出去。
                        群号必须明确给出，不确定时先用其它方式确认，不要凭猜测填。
                        注意：这是「主动发起」的消息，若当前对话就在这个群里，回复会自动送达，别再用本工具发一遍。""")
                .setRequired(List.of("groupId", "message"))
                .setParameters(List.of(
                        param("groupId", "string", "目标 QQ 群号"),
                        param("message", "string", "要发送的消息正文。用空行分段，每段作为一条独立消息发出")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        if (!StringUtils.hasText(arguments.getMessage())) {
            throw new ToolExecutor.ToolExecuteException("参数 message 不能为空");
        }
        if (!StringUtils.hasText(arguments.getGroupId())) {
            throw new ToolExecutor.ToolExecuteException("参数 groupId 不能为空");
        }

        Long groupId;
        try {
            groupId = Long.parseLong(arguments.getGroupId().strip());
        } catch (NumberFormatException e) {
            throw new ToolExecutor.ToolExecuteException("groupId 必须是群号数字，收到: " + arguments.getGroupId());
        }

        try {
            int count = qqBotService.sendGroupMessage(groupId, arguments.getMessage());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已发送到群 " + groupId + "，共 " + count + " 条");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("QQ 群消息发送失败: " + e.getMessage());
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

    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data
    private static class Arguments {
        private String groupId;
        private String message;
    }
}
