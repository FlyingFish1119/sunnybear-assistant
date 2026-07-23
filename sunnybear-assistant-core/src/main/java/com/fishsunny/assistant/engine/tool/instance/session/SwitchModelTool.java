package com.fishsunny.assistant.engine.tool.instance.session;

/*
 * @Usage Session 模型切换工具 —— 让 AI 自行决定是否启用 Pro 模型
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.SessionToolKit;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.variable.ControlSign;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

/**
 * 模型切换工具
 * 允许 AI 在对话过程中自行决定是否启用 Pro（高级）模型。
 * 模型名称从 AISettings 动态读取，切换后自动通知前端更新会话状态。
 */
@ToolKitComponent(SessionToolKit.class)
@ConditionalOnExpression("${engine.tool.session.enable:true} && ${engine.tool.session.switch-model.enable:true}")
public class SwitchModelTool implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(SwitchModelTool.class);

    public static final String NAME = "switch_model_tool";

    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;
    private final AISettings chatSettings;
    private final AISettings chatProSettings;

    public SwitchModelTool(ChatSessionService chatSessionService,
                           ObjectMapper objectMapper,
                           @Qualifier(AISettings.CHAT) AISettings chatSettings,
                           @Qualifier(AISettings.CHAT_PRO) AISettings chatProSettings) {
        this.chatSessionService = chatSessionService;
        this.objectMapper = objectMapper;
        this.chatSettings = chatSettings;
        this.chatProSettings = chatProSettings;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        // 解析参数
        boolean enablePro;
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
            if (!args.containsKey("enablePro")) {
                throw new ToolExecutor.ToolExecuteException("缺少必要参数 enablePro");
            }
            enablePro = Boolean.TRUE.equals(args.get("enablePro"));
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析失败: " + e.getMessage());
        }

        // 从上下文获取会话信息
        ChatSession chatSession = null;
        if (context.get("chatSession") instanceof ChatSession cs) {
            chatSession = cs;
        }
        if (chatSession == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息");
        }

        // 更新 enablePro 并持久化
        boolean oldValue = chatSession.getEnablePro() != null && chatSession.getEnablePro();
        if (oldValue == enablePro) {
            String modelName = enablePro ? "Pro（高级）" : "普通";
            return new ToolExecutor.ToolExecuteResponse(name(),
                    "模型已经是 " + modelName + "，无需切换。");
        }

        chatSession.setEnablePro(enablePro);
        chatSessionService.update(chatSession);

        // 通过 WebSocket 通知前端更新会话状态
        WebSocketSession wsSession = null;
        if (context.get("session") instanceof WebSocketSession wss) {
            wsSession = wss;
        }
        if (wsSession != null && wsSession.isOpen()) {
            try {
                String updateMessage = ControlSign.UPDATE_SESSION
                        + objectMapper.writeValueAsString(chatSession);
                wsSession.sendMessage(new TextMessage(updateMessage));
            } catch (Exception e) {
                log.warn("WebSocket 推送 UPDATE_SESSION 失败: {}", e.getMessage());
            }
        } else {
            log.warn("WebSocket 会话不可用，跳过前端通知");
        }

        String oldModel = oldValue ? "Pro（高级）" : "普通";
        String newModel = enablePro ? "Pro（高级）" : "普通";
        return new ToolExecutor.ToolExecuteResponse(name(),
                String.format("模型已切换：%s → %s。后续对话将使用 %s 模型。", oldModel, newModel, newModel));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        String normalModel = chatSettings.getModel();
        String proModel = chatProSettings.getModel();

        return new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        切换当前会话使用的 AI 模型级别。

                        【普通模型】%s
                        响应速度快、资源消耗低。

                        【Pro 模型】%s
                        推理能力强、擅长复杂任务。

                        【切换建议】
                        - 开启 Pro：当用户提出编程、深度推理、复杂分析等高难度任务时，主动切换到 Pro 以获得更好的效果。
                        - 切回普通：当复杂任务处理完毕，后续回归日常闲聊或简单问答时，切回普通模型以节省资源。
                        - 切忌频繁切换：在同一类任务中不要反复切换模型。
                        """.formatted(normalModel, proModel))
                .setRequired(List.of("enablePro"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("enablePro", "boolean",
                                ("true = 切换到 Pro 模型（%s，适合复杂任务）；false = 切换到普通模型（%s，适合日常对话）。")
                                        .formatted(proModel, normalModel))
                ));
    }
}
