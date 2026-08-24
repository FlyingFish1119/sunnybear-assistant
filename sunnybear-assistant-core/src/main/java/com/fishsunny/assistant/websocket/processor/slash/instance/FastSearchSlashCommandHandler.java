package com.fishsunny.assistant.websocket.processor.slash.instance;

/*
 * @Usage 快速联网搜索 —— 调用 net_explore_tool 快速模式获取双引擎原始结果，
 *        交给 chat ai 提炼成人可读的信息简报（流式输出，保留助手人设）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/24
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.net.NetExploreTool;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandComponent;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@SlashCommandComponent("/fast-search")
public class FastSearchSlashCommandHandler extends SlashCommandHandler {

    private static final String NO_TOOL_MESSAGE =
            "**fast-search 不可用**：`net_explore_tool` 当前未启用，请检查设置中的联网探索工具开关。";

    private final ChatMessageService chatMessageService;

    private final AssistantSettings assistantSettings;

    private final AISettings chatAiSettings;

    private final AISettings cubSettings;

    private final ChatHttpHandler chatHttpHandler;

    private final ObjectMapper objectMapper;

    /** net_explore_tool 为条件装配 Bean（可在设置中禁用），允许缺席 */
    private final ObjectProvider<NetExploreTool> netExploreProvider;

    public FastSearchSlashCommandHandler(ChatMessageService chatMessageService,
                                         AssistantSettings assistantSettings,
                                         @Qualifier(AISettings.CHAT) AISettings chatAiSettings,
                                         @Qualifier(AISettings.CUB) AISettings cubSettings,
                                         ChatHttpHandler chatHttpHandler,
                                         ObjectMapper objectMapper,
                                         ObjectProvider<NetExploreTool> netExploreProvider) {
        this.chatMessageService = chatMessageService;
        this.assistantSettings = assistantSettings;
        this.chatAiSettings = chatAiSettings;
        this.cubSettings = cubSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.netExploreProvider = netExploreProvider;
    }

    @Override
    protected List<String> resolveArgs(String originArgs) {
        // 关键字本身可能含空格，整体作为一个参数
        if (!StringUtils.hasText(originArgs)) {
            return Collections.emptyList();
        }
        return List.of(originArgs.trim());
    }

    @Override
    protected void handle(List<String> args) throws Exception {
        String keyword = args.isEmpty() ? "" : args.getFirst();

        // 参数校验
        if (!StringUtils.hasText(keyword)) {
            String usage = """
                    **用法**：`/fast-search <关键字>`

                    请提供要搜索的关键字，例如：`/fast-search Spring Virtual Threads 生产实践`。""";
            handleMessage(usage);
            return;
        }

        NetExploreTool netExploreTool = netExploreProvider.getIfAvailable();
        if (netExploreTool == null) {
            handleMessage(NO_TOOL_MESSAGE);
            return;
        }

        // ========== 第一步：net_explore_tool 快速模式（metaso + serper 双引擎原始结果） ==========
        String searchResultJson;
        try {
            String arguments = objectMapper.writeValueAsString(Map.of(
                    "target", keyword,
                    "fast", true
            ));
            ToolExecutor.ToolExecuteResponse response = netExploreTool.action(arguments, Map.of());
            searchResultJson = response.getResult();
        } catch (Exception e) {
            log.warn("/fast-search 搜索失败: {}", e.getMessage());
            handleMessage("**搜索失败**：" + e.getMessage());
            return;
        }
        if (!StringUtils.hasText(searchResultJson)) {
            handleMessage("**搜索失败**：搜索引擎未返回任何内容。");
            return;
        }

        // ========== 第二步：交给 chat ai 提炼成人可读的简报（保留助手人设） ==========
        String assistantPersona = StringUtils.hasText(chatAiSettings.getPrompt())
                ? chatAiSettings.getPrompt() : "（无特殊助手设定）";

        String systemPrompt = """
                # 角色
                你是一个「网络搜索结果提炼引擎」。你的职责是把一份原始搜索结果整理成一份人类易读的信息简报。

                # 核心规则
                1. 忠于原料：只使用搜索结果中实际存在的信息，禁止编造内容或虚构来源 URL。
                2. 主动去噪：剔除广告、SEO 农场、明显低质或完全重复的条目。
                3. 信息冲突时不擅自裁决，并列呈现并注明各自来源。
                4. 不确定的内容明确标注，不硬圆。

                # 输出格式（Markdown）
                先用 2-3 句话概括整体结论，然后：
                - 用 3-6 条要点列出最有价值的信息，每条末尾附上对应来源链接；
                - 最后附「来源」小节，逐条列出 `[标题](URL) — 一句话说明`。

                # 风格要求
                以下方「当前角色设定」中的身份、称呼和口吻输出整份简报，但不要做任何自我介绍；
                让读者感觉这份简报就是这位助手亲自搜集整理的，而不是一个冷冰冰的摘要机器。""";

        String userPrompt = """
                ## 当前角色设定
                %s

                ## 搜索关键字
                %s

                ## 原始搜索结果
                ```json
                %s
                ```

                请将上述搜索结果提炼成一份简洁、有条理、人类可读的信息简报。
                保留关键事实与来源链接，按系统提示词要求的格式输出。"""
                .formatted(assistantPersona, keyword, searchResultJson);

        ChatRequest request = new ChatRequest()
                .loadSettings(cubSettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
                ));

        String header = "## 🌐 快速搜索：`" + keyword + "`\n\n";
        AtomicBoolean isFirst = new AtomicBoolean(true);

        // ========== 第三步：流式推送 + 完成落盘，链路与 /look 保持一致 ==========
        chatHttpHandler.translate(chatSession.getId(), cubSettings.getAdapterName(), request, chatAiSettings.getStream(),
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
                        log.warn("流式推送 /fast-search chunk 失败: {}", e.getMessage());
                    }
                },
                (trResult, lastRes) -> {
                    try {
                        handleMessage(header + trResult.content());
                    } catch (Exception e) {
                        log.error("/fast-search 落盘失败: {}", e.getMessage());
                    }
                }
        );
    }

    private void handleMessage(String content) {
        ChatMessage msg = new ChatMessage()
                .assistant(content, "", List.of())
                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(messages), assistantSettings.getAssistantName());
        super.insertMessage(msg, chatMessageService);
        super.sendMessage(msg, objectMapper);
        super.resultMessage.add(msg);
    }
}
