package com.fishsunny.assistant.plug.qq.tool;

/*
 * @Usage QQ 主动私聊发送工具 —— 提醒、通知、定时任务结果上报
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
import com.fishsunny.assistant.plug.qq.config.QQBotOption;
import com.fishsunny.assistant.plug.qq.service.QQBotService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(QQToolKit.class)
@ConditionalOnExpression("${plug.qq.tool.enable:true} && ${plug.qq.tool.send_private_message.enable:true}")
public class QQSendPrivateMessageTool implements ToolHandler {

    public static final String NAME = "qq_send_private_message";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final QQBotService qqBotService;
    private final QQBotOption qqBotOption;

    public QQSendPrivateMessageTool(ObjectMapper objectMapper,
                                    QQBotService qqBotService,
                                    QQBotOption qqBotOption) {
        this.objectMapper = objectMapper;
        this.qqBotService = qqBotService;
        this.qqBotOption = qqBotOption;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        主动通过 QQ 给好友发消息，用于提醒、通知、定时任务的结果上报。
                        正文用空行分段，每一段会作为一条独立的消息连着发出去。
                        不填 userId 时默认发给白名单中的第一个 QQ 号。
                        注意：这是「主动发起」的消息。若当前对话本身就发生在 QQ 里，回复会自动送达，别再用本工具发一遍，否则对方会收到重复内容。""")
                .setRequired(List.of("message"))
                .setParameters(List.of(
                        param("message", "string", "要发送的消息正文。用空行分段，每段作为一条独立消息发出"),
                        param("userId", "string", "接收者 QQ 号；留空表示发给白名单中的第一个 QQ 号")
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

        Long userId = resolveUserId(arguments.getUserId());
        if (!qqBotOption.getReplayIds().contains(userId)) {
            throw new ToolExecutor.ToolExecuteException(
                    "QQ 号 " + userId + " 不在白名单内，拒绝发送。可在 plug.qq.bot.replay-ids 中追加");
        }

        try {
            int count = qqBotService.sendPrivateMessage(userId, arguments.getMessage());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已通过 QQ 发送给 " + userId + "，共 " + count + " 条");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("QQ 消息发送失败: " + e.getMessage());
        }
    }

    /** 未指定接收者时默认取白名单第一个号 */
    private Long resolveUserId(String raw) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(raw)) {
            List<Long> replayIds = qqBotOption.getReplayIds();
            if (replayIds == null || replayIds.isEmpty()) {
                throw new ToolExecutor.ToolExecuteException(
                        "未配置 QQ 白名单（plug.qq.bot.replay-ids），无法确定接收者");
            }
            return replayIds.getFirst();
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ToolExecutor.ToolExecuteException("userId 必须是 QQ 号数字，收到: " + raw);
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
        private String userId;
        private String message;
    }
}
