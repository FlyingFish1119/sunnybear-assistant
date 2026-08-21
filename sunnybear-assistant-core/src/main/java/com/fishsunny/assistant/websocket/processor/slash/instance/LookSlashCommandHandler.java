package com.fishsunny.assistant.websocket.processor.slash.instance;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/19 09:19
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandComponent;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandHandler;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SlashCommandComponent("/look")
public class LookSlashCommandHandler extends SlashCommandHandler {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(LookSlashCommandHandler.class);

    private final ChatMessageService chatMessageService;

    private final ChatHttpHandler chatHttpHandler;

    private final AISettings aiSettings;

    private final AssistantSettings assistantSettings;

    private final ObjectMapper objectMapper;

    public LookSlashCommandHandler(ChatMessageService chatMessageService,
                                   ChatHttpHandler chatHttpHandler,
                                   @Qualifier(AISettings.CHAT) AISettings aiSettings,
                                   AssistantSettings assistantSettings,
                                   ObjectMapper objectMapper
                                    ) {
        this.chatMessageService = chatMessageService;
        this.chatHttpHandler = chatHttpHandler;
        this.assistantSettings = assistantSettings;
        this.aiSettings = aiSettings;
        this.objectMapper = objectMapper;
    }

    @Override
    protected SlashCommand resolve(String originCommand) {
        List<String> parts = Arrays.asList(originCommand.split("\\s+", 3));
        return new SlashCommand(parts.getFirst(), parts.subList(1, parts.size()));
    }

    @Override
    protected void handle() throws Exception {
        String sessionId = super.chatSession.getId();

        String prompt = super.args.get(1);

        // 参数校验
        if (!StringUtils.hasText(sessionId)) {
            String usage = """
                    **用法**：`/look <sessionId> [关注点]`
                    
                    请提供要查看的会话 ID。在输入框中输入 `/look` 可从侧边栏选择会话。""";
            ChatMessage errorMsg = new ChatMessage()
                    .assistant(usage, "", List.of())
                    .setSessionId(sessionId);
            super.resultMessage.add(errorMsg);
            return;
        }

        // 拉取历史
        List<ChatMessage> history;
        try {
            history = chatMessageService.getConversationHistory(sessionId.trim());
        } catch (Exception e) {
            String err = "**查询失败**：会话 `" + sessionId + "` 不存在或无法访问。";
            ChatMessage errorMsg =  new ChatMessage()
                    .assistant(err, "", List.of())
                    .makeInsertable(chatSession.getId(), ChatMessage.getParentId(originMessages), assistantSettings.getAssistantName());
            super.insertMessage(errorMsg, chatMessageService);
            super.sendMessage(errorMsg, objectMapper);
            super.resultMessage.add(errorMsg);
            return;
        }
        if (!StringUtils.hasText(sessionId)) {
            String empty = "**会话 `" + sessionId + "` 暂无对话记录。**";
            ChatMessage emptyMsg = new ChatMessage()
                    .assistant(empty, "", List.of())
                    .makeInsertable(chatSession.getId(), ChatMessage.getParentId(originMessages), assistantSettings.getAssistantName());

            super.resultMessage.add(emptyMsg);
            return;
        }

        // 拼接对话历史
        StringBuilder historyText = new StringBuilder();
        for (ChatMessage msg : history) {
            String role = msg.getRole();
            String text = msg.resolveText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String label = switch (role) {
                case "user" -> "用户";
                case "assistant" -> "助手";
                case "tool" -> "工具";
                default -> role;
            };
            historyText.append("[").append(label).append("] ")
                    .append(msg.getName() == null ? "" : msg.getName() + "：").append(text)
                    .append("\n\n");
        }

        // 提取 chat AI 的系统提示词，与待总结文本一同放入 user prompt
        String focusLine = StringUtils.hasText(prompt) ? "请重点关注以下方面：" + prompt + "\n" : "";
        String chatSystemPrompt = aiSettings.getPrompt() != null ? aiSettings.getPrompt() : "";

        String userPrompt = """
                ## 当前角色设定
                %s
                
                ## 会话 ID：%s
                %s
                ## 对话历史
                %s
                
                请以当前角色设定的视角，对上述对话历史生成一段简洁有条理的摘要（3-5 段）。
                如果指定了关注重点，请围绕该重点展开；否则概括全文的要点和关键信息。
                使用 Markdown 格式输出，包含标题和分点。"""
                .formatted(
                        StringUtils.hasText(chatSystemPrompt) ? chatSystemPrompt : "（无特殊角色设定）",
                        sessionId.trim(),
                        focusLine,
                        historyText.toString()
                );

        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(new ChatMessage().user(userPrompt)));

        String header = "## 📜 会话摘要：`" + sessionId.trim() + "`\n\n";
        AtomicBoolean isFirst = new AtomicBoolean(true);
        chatHttpHandler.translate(chatSession.getId(), aiSettings.getAdapterName(), request, aiSettings.getStream(),
                tr -> {
                    ChatResponse masterResp = (ChatResponse) tr;
                    if (isFirst.get() && StringUtils.hasText(masterResp.getText())) {
                        masterResp.appendTextAtStart(header);
                        isFirst.set(false);
                    }
                    masterResp.setSessionId(chatSession.getId());
                    try {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(masterResp)));
                    } catch (Exception e) {
                        log.warn("流式推送 /look chunk 失败: {}", e.getMessage());
                    }
                },
                (trResult, lastRes) -> {
                    try {
                        ChatMessage savedMessage = new ChatMessage()
                                .assistant(header + trResult.content(), "", List.of())
                                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(originMessages), assistantSettings.getAssistantName());
                        super.insertMessage(savedMessage, chatMessageService);
                        super.sendMessage(savedMessage, objectMapper);
                        super.resultMessage.add(savedMessage);
                    } catch (Exception e) {
                        log.error("/look 落盘失败: {}", e.getMessage());
                    }
                }
        );
    }
}
