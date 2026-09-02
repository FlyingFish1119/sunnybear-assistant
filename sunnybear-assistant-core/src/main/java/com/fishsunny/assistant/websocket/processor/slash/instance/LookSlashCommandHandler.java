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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@SlashCommandComponent("/look")
public class LookSlashCommandHandler extends SlashCommandHandler {

    private final ChatMessageService chatMessageService;

    private final ChatHttpHandler chatHttpHandler;

    private final AISettings chatAiSettings;

    private final AISettings aiSettings;

    private final AssistantSettings assistantSettings;

    private final ObjectMapper objectMapper;

    public LookSlashCommandHandler(ChatMessageService chatMessageService,
                                   ChatHttpHandler chatHttpHandler,
                                   @Qualifier(AISettings.CHAT) AISettings chatAiSettings,
                                   @Qualifier(AISettings.CUB) AISettings aiSettings,
                                   AssistantSettings assistantSettings,
                                   ObjectMapper objectMapper
    ) {
        this.chatMessageService = chatMessageService;
        this.chatHttpHandler = chatHttpHandler;
        this.chatAiSettings = chatAiSettings;
        this.assistantSettings = assistantSettings;
        this.aiSettings = aiSettings;
        this.objectMapper = objectMapper;
    }

    @Override
    protected List<String> resolveArgs(String originArgs) {
        return Arrays.asList(originArgs.split("\\s+", 2));
    }

    @Override
    protected void handle(List<String> args) throws Exception {
        String sessionId = args.isEmpty() ? "" : args.getFirst();

        // 参数校验
        if (!StringUtils.hasText(sessionId)) {
            String usage = """
                    **用法**：`/look <sessionId> [关注点]`
                    
                    请提供要查看的会话 ID。在输入框中输入 `/look` 可从侧边栏选择会话。""";
            handleMessage(usage, "");
            return;
        }

        // 拉取历史
        List<ChatMessage> history;
        try {
            history = chatMessageService.getConversationHistory(sessionId.trim());
        } catch (Exception e) {
            String err = "**查询失败**：会话 `" + sessionId + "` 不存在或无法访问。";
            handleMessage(err, "");
            return;
        }
        if (CollectionUtils.isEmpty(history)) {
            String empty = "**会话 `" + sessionId + "` 暂无对话记录。**";
            handleMessage(empty, "");
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
        String prompt = args.size() < 2 ? "" : args.get(1);
        String focusLine = StringUtils.hasText(prompt) ? "请重点关注以下方面：" + prompt + "\n" : "";
        String chatSystemPrompt = StringUtils.hasText(chatAiSettings.getPrompt()) ? chatAiSettings.getPrompt() : "（无特殊助手设定）";

        String systemPrompt = """
                # 角色
                你是一个「对话总结与风格复现引擎」。你的工作是模仿助手设定来写对话摘要：拿到一段长对话 + 一位助手的设定后，你要以那位助手的身份、语气、习惯，把对话里真正重要的东西提炼并复述出来。
                
                # 输入
                你会收到两部分内容：
                <助手设定> ← 目标助手的完整人设/系统设定
                <对话文本> ← 待总结的原始长对话
                
                # 核心流程（顺序不可颠倒）
                第一步：代入。完整吃透 <助手设定>，把自己彻底"变成"那个助手——它的称呼习惯、说话节奏、口头禅、情感温度、专业领域，以及它的禁忌（什么不能说、什么格式不能碰）。
                第二步：提取。通读 <对话文本>，只捞有价值的信息。
                第三步：复现。以那位助手的口吻输出总结。
                
                # 风格复现
                动笔前，先从 <助手设定> 里逐条拆出并严格遵守：
                - 称呼：它怎么叫对话对象
                - 语气与温度：冷淡/温柔/专业/毒舌/依赖……照搬，绝不掺入你自己的性格
                - 句式节奏：长短句、口语还是书面、标点与换行习惯
                - 常用表达：口头禅、语气词、开场白、收尾方式
                - 禁忌项：设定里明确禁止的表达，总结中同样禁止
                
                验收标准：读者读这篇总结时，应该觉得"就是那个助手本人写的"，而不是"一个 AI 写的摘要"。
                
                # 总结需覆盖的内容
                从 <对话文本> 中抽取，原文没有的宁可标"未提及"，不许脑补：
                1. 对话主题 / 核心目标
                2. 关键结论与决策
                3. 待办事项 / 行动项（含负责人、截止时间，仅当原文有）
                4. 关键事实 / 数据 / 约束
                5. 情绪与关系信号（对方的态度变化、隐含诉求等，需明确标注为"推断"）
                
                # 硬性约束
                - 忠于原文：不编造对话中没有的细节、数据、承诺。
                - 不越权：不替对话双方做任何原文里没出现的新决定。
                - 风格纯粹：输出只带目标助手的风格。禁止冒出你自己的"AI 腔"——如"综上所述""作为 AI""希望以上对您有帮助"这类套话，除非目标助手本人也这么说话。
                - 不确定处明确标注，不硬圆。
                """;

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
                        chatSystemPrompt,
                        sessionId.trim(),
                        focusLine,
                        historyText.toString()
                );

        String header = "## 📜 会话摘要：`" + sessionId.trim() + "`\n\n";
        AtomicBoolean isFirst = new AtomicBoolean(true);

        // 构建请求
        ChatRequest request = new ChatRequest().quickBuild(userPrompt, systemPrompt, chatAiSettings);
        // 构建数据
        ChatHttpHandler.TranslateData data = new ChatHttpHandler.TranslateData(chatSession.getId(), aiSettings.getAdapterName(), chatAiSettings.getStream(), request);
        // 构建处理器
        ChatHttpHandler.TranslateHandler handler = new ChatHttpHandler.TranslateHandler(
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
                        handleMessage(header + trResult.content(), trResult.reasoning());
                    } catch (Exception e) {
                        log.error("/look 落盘失败: {}", e.getMessage());
                    }
                }
        );


        chatHttpHandler.translate(data, handler);
    }

    private void handleMessage(String content, String reasoning) {
        ChatMessage msg =  new ChatMessage()
                .assistant(content, reasoning, List.of())
                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(messages), assistantSettings.getAssistantName());
        super.insertMessage(msg, chatMessageService);
        super.sendMessage(msg, objectMapper);
        super.resultMessage.add(msg);
    }
}
