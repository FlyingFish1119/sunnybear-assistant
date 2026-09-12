package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 执行型子 Agent（Mission/Cub 分工）—— 主 Agent 把活派进来，它去做具体的事。
 *        首次召唤时由 Cub AI 针对当前任务为它生成一份专注任务目标的系统提示词，常驻整个会话；
 *        之后它以 Mission AI 为模型、在 ToolCallLoop 里自己调工具自己干，跨轮共享一棵会话内的持久消息树，
 *        所以任务没一次干完时它能接着上次的进展继续。由 agent_tool 路由调用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/11
 */

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.engine.tool.instance.flow.QuestionTool;
import com.fishsunny.assistant.engine.tool.service.ToolVisibilityPolicy;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${engine.tool.agent.clone-assistant.enable:true}")
public class CloneAssistantTool implements SubAgentToolHandler {

    public static final String NAME = "clone_assistant_tool";

    private static final Logger log = LoggerFactory.getLogger(CloneAssistantTool.class);

    /** 消息树文件名，落在会话的 file 目录下 */
    private static final String MESSAGE_TREE_FILE = "clone-assistant-message-tree.json";

    /** Cub 生成的专注任务系统提示词文件名，与消息树同目录 */
    private static final String MISSION_PROMPT_FILE = "clone-assistant-mission-prompt.txt";

    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final AISettings cubAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;
    private final SessionFileManager sessionFileManager;
    private final ToolVisibilityPolicy toolVisibilityPolicy;
    private final ToolRegister register;
    private final Map<String, ToolHandler> registry;

    public CloneAssistantTool(ObjectMapper objectMapper,
                              @Lazy ToolExecutor toolExecutor,
                              ToolCallLoop toolCallLoop,
                              SessionFileManager sessionFileManager,
                              ChatHttpHandler chatHttpHandler,
                              @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                              @Qualifier(AISettings.CUB) AISettings cubAISettings,
                              ToolVisibilityPolicy toolVisibilityPolicy,
                              List<SubAgentToolHandler> subAgents
    ) {
        this.registry = new HashMap<>();
        for (SubAgentToolHandler subAgent : subAgents) {
            if (subAgent.name().equals(NAME)) {
                continue;
            }
            registry.put(subAgent.name(), subAgent);
        }

        this.objectMapper = objectMapper;
        this.chatHttpHandler = chatHttpHandler;
        this.toolExecutor = toolExecutor;
        this.toolCallLoop = toolCallLoop;
        this.sessionFileManager = sessionFileManager;
        this.missionAISettings = missionAISettings;
        this.cubAISettings = cubAISettings;
        this.toolVisibilityPolicy = toolVisibilityPolicy;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        执行型子 Agent。把一件事派给它去做：说清楚目标和必要的上下文，它自己动手、\
                        自己调工具（包括召唤别的子 Agent）、自己推进，最后返回执行结果和遇到的问题。\
                        它持有本会话的执行记录，任务没一次干完时能接着上次的进展继续干，\
                        也会在结果里说明它缺什么、卡在哪。
                        适合：可以独立交付的工作——探索目录、联查资料、改代码、跑任务这类完整执行段。\
                        不适合：一句话能答复的事、方案讨论、必须由你亲自拍板的判断。""")
                .setRequired(List.of("target"))
                .setParameters(List.of(new ToolRegister.Parameters(
                        "target", "string",
                        """
                        要执行的任务，像交接工作一样交代清楚：目标、验收标准、已知的背景与约束。\
                        首次召唤时它会以此生成专属执行方案并常驻整个会话，所以第一次要说透；\
                        后续召唤它可以接着上次的进展继续干，不用重复交代。""")));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session", "chatRequest"}, type = {ChatSession.class, WebSocketSession.class, ChatRequest.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null || !StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空，需说明要执行的任务");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");

        try {
            // 1. 首次召唤时由 Cub 生成本任务的专注执行系统提示词并落盘；之后直接复用
            Path promptFile = sessionFileManager.prepareSessionFile(chatSession.getId(), MISSION_PROMPT_FILE);
            String missionPrompt = readMissionPrompt(promptFile);
            if (missionPrompt == null) {
                missionPrompt = generateMissionPrompt(arguments.getTarget());
                writeMissionPrompt(missionPrompt, promptFile);
            }

            // 2. 读树：首次召唤时文件还不存在，视为空树
            Path treeFile = sessionFileManager.prepareSessionFile(chatSession.getId(), MESSAGE_TREE_FILE);
            List<ChatMessage> tree = readMessageTree(treeFile);

            // 3. 本轮任务追加进树，成为执行体看到的最后一条 user
            tree.add(new ChatMessage().user(arguments.getTarget()));
            int treeSize = tree.size();

            // 4. 组装请求：专注提示词 + 整棵树。
            //    不需要在此展开文件引用——ToolCallLoop 每轮 translate 前会统一展开一遍，
            //    而树里的消息和 request 里的是同一批对象，展开结果会随第 6 步一起落盘。
            List<ChatMessage> sendMessages = new ArrayList<>();
            sendMessages.add(new ChatMessage().system(missionPrompt));
            sendMessages.addAll(tree);

            // Mission AI 执行，固定开 Thinking
            AISettings settings = new AISettings().copy(missionAISettings).setStream(false).setThinking(true);
            ChatRequest request = new ChatRequest()
                    .loadSettings(settings)
                    .setMessages(sendMessages)
                    .setTools(buildToolRegisters());

            // 5. 跑循环：执行体自己调工具、自己推敲，中间产生的 assistant / tool 消息由 ToolCallLoop 追加在 sendMessages 尾部
            String finalText = toolCallLoop.execute(settings, request, context);

            // 6. 把本轮新增的消息（执行体的答复、它自己的工具调用与结果）接回树并落盘。
            //    tree 与 sendMessages 里的消息是同一批对象，循环内每轮开头的展开都已在其中生效
            tree.addAll(sendMessages.subList(1 + treeSize, sendMessages.size()));
            writeMessageTree(tree, treeFile);

            return new ToolExecutor.ToolExecuteResponse(name(), finalText);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("执行型子 Agent 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("执行型子 Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行体这次能用的工具表：主 Agent 那套（全量减 ToolVisibilityPolicy 判定的排除项），
     * 再剔除 question_tool（执行型不向用户采集答案，有问题在最终回复里直接说），
     * 并把 agent_tool 里"能召唤哪些子 Agent"的说明换成去掉它自己的版本。
     * <p>
     * 为什么要换 agent_tool：AgentTool 那份说明是它构造时按全部子 Agent 拼的、全局就一份，里面带着
     * clone_assistant_tool——直接给执行体，等于告诉它"你可以召唤你自己"。
     * 子 Agent 互相召唤是允许的（file_explore、net_explore 都该留着），只有自己不能再套自己。
     * <p>
     * 改这份不会污染别人：buildToolRegisterExcluding 每次调用都现造一批新的
     * StandardToolRegister（连同 parameters.properties 里那些对象），不是共享的注册对象。
     * 注意两处都要换：function 描述和 agent 参数的描述各拼了一份名字表，漏一处照样广告出去。
     */
    List<StandardToolRegister> buildToolRegisters() throws ToolExecutor.ToolExecuteException {
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterExcluding(
                toolExecutor, toolVisibilityPolicy.excludedKits(), toolVisibilityPolicy.excludedHandlers())
                .stream()
                .filter(tool -> !tool.getFunction().getName().equals(QuestionTool.NAME))
                .collect(Collectors.toList());
        StandardToolRegister agentToolRegister = toolRegisters.stream()
                .filter(tool -> tool.getFunction().getName().equals(AgentTool.NAME))
                .findFirst()
                .orElseThrow(() -> new ToolExecutor.ToolExecuteException("工具注册表中不存在 " + AgentTool.NAME + "，无法为执行体裁剪子 Agent 说明"));

        agentToolRegister.getFunction().setDescription(AgentToolKit.getSubDescription(registry));
        agentToolRegister.getFunction().getParameters()
                .getProperties().get(AgentTool.PARAM_AGENT)
                .setDescription(AgentToolKit.getAgentParamDescription(registry));
        return toolRegisters;
    }

    /**
     * 用 Cub 针对本次任务生成专注任务目标的执行系统提示词（常驻整个会话）。
     * <p>
     * Cub 的产出是一份角色人设——以"你是一个高级软件工程师…"开头的身份式提示词，
     * 由它去补齐任务所需的专业身份与工作守则；任务本身的执行指令由主 Agent 的 target 承担，
     * 两边职责分开，避免提示词和派下来的任务打架。
     * <p>
     * Cub 调用走 translate 的新签名（TranslateData/TranslateHandler/TranslateOption），
     * 同步调用 + CompleteCallback 收内容，返回后即收完。
     * 生成失败不阻塞执行：回退到一份通用的身份式提示词，保证活还能干。
     */
    private String generateMissionPrompt(String target) {
        String writerPrompt = """
                你是一位系统提示词工程师，负责为一个执行型子 Agent 撰写角色设定（系统提示词）。
                主 Agent 会把一项任务派给这个子 Agent 去执行，你要根据下面的任务描述，写出这份角色设定。

                要求：
                1. 用"你是一个……"的身份句式开头，为子 Agent 赋予完成该任务所需的专业身份（如高级软件工程师、调研分析师）。
                2. 围绕任务写身份配套的工作守则、质量标准与注意事项，让子 Agent 拿到就能高效干活。
                3. 不要复述任务原文、不要写具体执行步骤——任务内容会由主 Agent 单独下达，你只负责"这个角色该怎么做活"。
                4. 不幻觉任务里不存在的背景；只输出提示词本身，不要任何解释、前言或代码块包裹。
                5. 简体中文，篇幅精炼。

                任务描述：
                %s
                """.formatted(target);

        ChatRequest request = new ChatRequest().quickBuild(writerPrompt, target, cubAISettings);
        try {
            StringBuilder prompt = new StringBuilder();
            chatHttpHandler.translate(
                    new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request),
                    new ChatHttpHandler.TranslateHandler(null, (trResult, lastRes) -> {
                        String content = trResult.content();
                        if (content != null) {
                            prompt.setLength(0);
                            prompt.append(content.trim());
                        }
                    }),
                    new ChatHttpHandler.TranslateOption().setStream(cubAISettings.getStream()));
            if (prompt.isEmpty()) {
                throw new IllegalStateException("Cub 返回了空内容");
            }
            return prompt + APPENDIX_PROMPT;
        } catch (Exception e) {
            log.warn("Cub 生成专注提示词失败，回退到最简执行提示词: {}", e.getMessage());
            return "你是一个执行型子 Agent，替主 Agent 执行交派的任务。任务指令由主 Agent 随消息下达，照做即可。"
                    + APPENDIX_PROMPT;
        }
    }

    /**
     * 追加在提示词末尾的行为约束。短小、稳定、不随任务变——该由提示词管的规则放这里固化，
     * 不指望 Cub 每次生成都记得。
     */
    private static final String APPENDIX_PROMPT = """


            ## 行为约束

            - **拿不准就先问，不要硬猜。** 任务有歧义、关键信息缺失、有多种合理走法拿不定主意时，\
              停下手直接在回复里把问题说清楚，交回给主 Agent 对齐后再动手；不要按自己的猜测直接执行。\
            - **收在结果上。** 最终回复讲清楚做了什么、结果如何、验证依据；没做完或被问题卡住的，\
              明确说明卡在哪、需要什么。""";

    // ==================== 专注提示词读写 ====================

    /**
     * 读会话内的专注提示词。文件不存在或为空返回 null——首次召唤是正常路径，不是错误。
     */
    private String readMissionPrompt(Path promptFile) throws Exception {
        if (!Files.exists(promptFile)) {
            return null;
        }
        String prompt = Files.readString(promptFile, StandardCharsets.UTF_8);
        return StringUtils.hasText(prompt) ? prompt : null;
    }

    private void writeMissionPrompt(String prompt, Path promptFile) throws Exception {
        Path tmp = promptFile.resolveSibling(promptFile.getFileName() + ".tmp");
        Files.writeString(tmp, prompt, StandardCharsets.UTF_8);
        Files.move(tmp, promptFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // ==================== 消息树读写 ====================

    /**
     * 读消息树。文件不存在或内容为空时返回空树——首次召唤是正常路径，不是错误。
     */
    private List<ChatMessage> readMessageTree(Path treeFile) {
        if (!Files.exists(treeFile)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(treeFile);
            if (bytes.length == 0) {
                return new ArrayList<>();
            }
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class);
            List<ChatMessage> messages = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), listType);
            return messages == null ? new ArrayList<>() : messages;
        } catch (Exception e) {
            // 树解析失败是致命的：不修的话每次召唤都会在这里挂掉。把路径抛出去，便于人工删除重建
            throw new RuntimeException("执行型子 Agent 消息树解析失败，请检查或删除该文件后重试: " + treeFile.toAbsolutePath()
                    + "，原因: " + e.getMessage(), e);
        }
    }

    /**
     * 写消息树。先写临时文件再原子替换，避免写到一半崩了把整棵树写坏。
     */
    private void writeMessageTree(List<ChatMessage> messages, Path treeFile) throws Exception {
        // 目录已由 SessionFileManager.prepareSessionFile 建好
        Path tmp = treeFile.resolveSibling(treeFile.getFileName() + ".tmp");
        Files.writeString(tmp, objectMapper.writeValueAsString(messages), StandardCharsets.UTF_8);
        Files.move(tmp, treeFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // ==================== 基础方法 ====================

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String target;
    }
}
