package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 执行型子 Agent（Mission/Cub 分工）—— 主 Agent 把活派进来，它去做具体的事。
 *        首次召唤时由「执行体同级模型」（派活用哪档模型，写提示词就用哪档）针对当前任务
 *        生成一份专注任务目标的系统提示词，常驻整个会话；之后它以调用方传入的 model key
 *        从「执行体模型列表」（内置场景条目实时注入 + json 追加）查得的模型干活，
 *        在 ToolCallLoop 里自己调工具自己推进，跨轮共享一棵会话内的持久消息树，
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
import com.fishsunny.assistant.utils.EasyReActProcessor;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.engine.tool.instance.flow.QuestionTool;
import com.fishsunny.assistant.engine.tool.service.ToolVisibilityPolicy;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.CloneModelSettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${engine.tool.agent.clone-assistant.enable:true}")
public class CloneAssistantTool implements SubAgentComponent {

    public static final String NAME = "clone_assistant_tool";

    /** 执行体专属子目录：会话 file 目录下，收拢消息树与专注提示词，不与用户文件混在根上（同命令日志收进 logs/ 的做法） */
    private static final String CLONE_DIR = "clone-assistant";

    /** 消息树文件，位于会话 file 目录的 clone-assistant/ 子目录下 */
    private static final String MESSAGE_TREE_FILE = CLONE_DIR + "/message-tree.json";

    /** 同级模型生成的专注任务系统提示词，与消息树同目录 */
    private static final String MISSION_PROMPT_FILE = CLONE_DIR + "/mission-prompt.txt";

    private final ObjectMapper objectMapper;
    private final CloneModelSettings cloneModelSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final EasyReActProcessor easyReActProcessor;
    private final ToolExecutor toolExecutor;
    private final SessionFileManager sessionFileManager;
    private final ToolVisibilityPolicy toolVisibilityPolicy;
    private final Map<String, SubAgentComponent> registry;

    public CloneAssistantTool(ObjectMapper objectMapper,
                              @Lazy ToolExecutor toolExecutor,
                              EasyReActProcessor easyReActProcessor,
                              SessionFileManager sessionFileManager,
                              ChatHttpHandler chatHttpHandler,
                              CloneModelSettings cloneModelSettings,
                              ToolVisibilityPolicy toolVisibilityPolicy,
                              List<SubAgentComponent> subAgents
    ) {
        this.registry = new HashMap<>();
        for (SubAgentComponent subAgent : subAgents) {
            if (subAgent.name().equals(NAME)) {
                continue;
            }
            registry.put(subAgent.name(), subAgent);
        }

        this.objectMapper = objectMapper;
        this.chatHttpHandler = chatHttpHandler;
        this.toolExecutor = toolExecutor;
        this.easyReActProcessor = easyReActProcessor;
        this.sessionFileManager = sessionFileManager;
        this.cloneModelSettings = cloneModelSettings;
        this.toolVisibilityPolicy = toolVisibilityPolicy;
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session", "chatRequest"}, type = {ChatSession.class, WebSocketSession.class, ChatRequest.class})
    public ToolExecutor.ToolExecuteResponse action(SubAgentComponent.SubAgentArguments arguments, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        String modelKey = extractModelKey(arguments);
        if (!StringUtils.hasText(modelKey)) {
            throw new ToolExecutor.ToolExecuteException("参数 model 不能为空，需从工具说明中列出的执行体模型 key 中选择一个");
        }
        ChatSession chatSession = (ChatSession) context.get("chatSession");

        try {
            // 0. clear=true 时先清空已有执行记录，再走后续完整流程（clear 同样经 extension 透传）
            if (readClear(arguments.getExtension())) {
                clearSession(chatSession.getId());
            }

            // 1. 按传入的 model key 查「执行体模型列表」的合并视图：内置场景条目（chat / chat_pro /
            //    operator / mission / cub，实时跟随场景设置）+ json 追加条目（key 撞场景名时用户覆盖优先）。
            //    查表提前到生成提示词之前——同级生成要用同一份模型设置，适配器、模型与思考档位由条目决定。
            CloneModelSettings.ModelEntry modelEntry = cloneModelSettings.findEntry(modelKey);
            if (modelEntry == null) {
                if (cloneModelSettings.getMergedModels().isEmpty()) {
                    throw new ToolExecutor.ToolExecuteException("执行体模型列表为空（场景模型均未配置且无追加模型），无法执行任务；"
                            + "请让用户到设置页「AI 设置」先为各场景配置模型，或到「执行体模型列表」追加条目后再试");
                }
                throw new ToolExecutor.ToolExecuteException("未知的执行体模型 key: " + modelKey + "，可用 key 如下：\n"
                        + cloneModelSettings.describeKeys());
            }
            AISettings settings = new AISettings()
                    .setAdapterName(modelEntry.getAdapterName())
                    .setModel(modelEntry.getModel())
                    .setStream(false)
                    .setThinking(Boolean.TRUE.equals(modelEntry.getThinking()))
                    .setReasoningEffort(modelEntry.getReasoningEffort());

            // 2. 首次召唤时由「执行体同级模型」生成本任务的专注执行系统提示词并落盘；之后直接复用。
            //    同级生成：派活用哪档模型，写提示词就用哪档——任务复杂时主 Agent 自然选高级模型，
            //    提示词质量与任务档位自动对齐；简单任务轻量档自己写个简单壳子，成本与需求同频。
            Path promptFile = sessionFileManager.prepareSessionFile(chatSession.getId(), MISSION_PROMPT_FILE);
            String missionPrompt = readMissionPrompt(promptFile);
            if (missionPrompt == null) {
                missionPrompt = generateMissionPrompt(arguments.getTarget(), settings);
                writeMissionPrompt(missionPrompt, promptFile);
            }

            // 3. 读树：首次召唤时文件还不存在，视为空树
            Path treeFile = sessionFileManager.prepareSessionFile(chatSession.getId(), MESSAGE_TREE_FILE);
            List<ChatMessage> tree = readMessageTree(treeFile);

            // 4. 本轮任务追加进树，成为执行体看到的最后一条 user
            tree.add(new ChatMessage().user(arguments.getTarget()));
            int treeSize = tree.size();

            // 5. 组装请求：专注提示词 + 整棵树。
            //    不需要在此展开文件引用——ToolCallLoop 每轮 translate 前会统一展开一遍，
            //    而树里的消息和 request 里的是同一批对象，展开结果会随第 7 步一起落盘。
            List<ChatMessage> sendMessages = new ArrayList<>();
            sendMessages.add(new ChatMessage().system(missionPrompt));
            sendMessages.addAll(tree);

            ChatRequest request = new ChatRequest()
                    .loadSettings(settings)
                    .setMessages(sendMessages)
                    .setTools(buildToolRegisters());

            // 6. 跑循环：执行体自己调工具、自己推敲，中间产生的 assistant / tool 消息由 ToolCallLoop 追加在 sendMessages 尾部
            String finalText = easyReActProcessor.execute(settings, request, context);

            // 7. 把本轮新增的消息（执行体的答复、它自己的工具调用与结果）接回树并落盘。
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
     * 最后经 AgentToolKit.trimAgentTool 把 agent_tool 的可召唤说明换成去掉它自己的版本——
     * registry 是构造时就不含 clone_assistant_tool 的子 Agent 路由表，防自召的细节见该方法注释。
     */
    List<StandardToolRegister> buildToolRegisters() throws ToolExecutor.ToolExecuteException {
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterExcluding(
                        toolExecutor, toolVisibilityPolicy.excludedKits(), Set.of())
                .stream()
                .filter(tool -> !tool.getFunction().getName().equals(QuestionTool.NAME))
                .collect(Collectors.toList());
        AgentToolKit.trimAgentTool(toolRegisters, registry);
        return toolRegisters;
    }

    /**
     * 用「执行体同级模型」针对本次任务生成专注任务目标的执行系统提示词（常驻整个会话）。
     * <p>
     * 同级生成：settings 就是本次执行体干活用的那份模型设置——派活选了哪档模型，
     * 提示词就由哪档写。任务复杂时主 Agent 自然选高级模型，提示词质量跟着升级；
     * 简单任务轻量档自己写个简单壳子，成本与需求同频，也不存在弱模型给强模型定守则的倒挂。
     * <p>
     * 产出是一份角色人设——以"你是一个高级软件工程师…"开头的身份式提示词，
     * 由它去补齐任务所需的专业身份与工作守则；任务本身的执行指令由主 Agent 的 target 承担，
     * 两边职责分开，避免提示词和派下来的任务打架。
     * <p>
     * 调用走 translate 的新签名（TranslateData/TranslateHandler/TranslateOption），
     * 同步调用 + CompleteCallback 收内容，返回后即收完。
     * 生成失败不阻塞执行：回退到一份通用的身份式提示词，保证活还能干。
     */
    private String generateMissionPrompt(String target, AISettings settings) {
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

        ChatRequest request = new ChatRequest().quickBuild(writerPrompt, target, settings);
        try {
            StringBuilder prompt = new StringBuilder();
            chatHttpHandler.translate(
                    new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), settings.getAdapterName(), request),
                    new ChatHttpHandler.TranslateHandler(null, (trResult, lastRes) -> {
                        String content = trResult.content();
                        if (content != null) {
                            prompt.setLength(0);
                            prompt.append(content.trim());
                        }
                    }),
                    new ChatHttpHandler.TranslateOption().setStream(settings.getStream()));
            if (prompt.isEmpty()) {
                throw new IllegalStateException("同级模型返回了空内容");
            }
            return prompt + APPENDIX_PROMPT;
        } catch (Exception e) {
            log.warn("同级模型生成专注提示词失败，回退到最简执行提示词: {}", e.getMessage());
            return "你是一个执行型子 Agent，替主 Agent 执行交派的任务。任务指令由主 Agent 随消息下达，照做即可。"
                    + APPENDIX_PROMPT;
        }
    }

    /**
     * 追加在提示词末尾的行为约束。短小、稳定、不随任务变——该由提示词管的规则放这里固化，
     * 不指望同级模型每次生成都记得。
     */
    private static final String APPENDIX_PROMPT = """


            ## 行为约束

            - **拿不准就先问，不要硬猜。** 任务有歧义、关键信息缺失、有多种合理走法拿不定主意时，\
              停下手直接在回复里把问题说清楚，交回给主 Agent 对齐后再动手；不要按自己的猜测直接执行。\
            - **收在结果上。** 最终回复讲清楚做了什么、结果如何、验证依据；没做完或被问题卡住的，\
              明确说明卡在哪、需要什么。""";

    // ==================== 执行记录清理 ====================

    /**
     * 清空该会话内执行体积攒的执行记录：消息树与专注提示词。
     * 文件不存在属正常路径，忽略；清理后由后续流程重新生成、从空树开始。
     */
    private void clearSession(String sessionId) throws Exception {
        Files.deleteIfExists(sessionFileManager.prepareSessionFile(sessionId, MESSAGE_TREE_FILE));
        Files.deleteIfExists(sessionFileManager.prepareSessionFile(sessionId, MISSION_PROMPT_FILE));
        log.info("执行型子 Agent 已清空执行记录: session={}", sessionId);
    }

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
    public String description() {
        return """
                执行型子 Agent。把一件事派给它去做：说清楚目标和必要的上下文，它自己动手、\
                自己调工具（包括召唤别的子 Agent）、自己推进，最后返回执行结果和遇到的问题。\
                它持有一个独立的上下文，任务没一次干完时能接着上次的进展继续干。\
                也会在结果里说明它缺什么、卡在哪。\
                适合：可以独立交付的工作——探索目录、联查资料、改代码、跑任务这类完整执行段。\
                不适合：一句话能答复的事、方案讨论、必须由你亲自拍板的判断。""";
    }

    /**
     * 向 agent_tool 申报本子 Agent 的专属扩展参数（model / clear），经 extension 透传，
     * 由 AgentToolKit.getSubDescription 拼进本工具的介绍条目后面。
     * 每次现调现拼：设置页改完「执行体模型列表」下一轮对话即可见。
     */
    @Override
    public List<ToolRegister.Parameters> extensionProperties() {
        return List.of(
                new ToolRegister.Parameters(
                        "model", "string",
                        "执行体使用的模型 key，必传。按任务性质从下列 key 中选择合适的模型：\n"
                                + cloneModelSettings.describeKeys()),
                new ToolRegister.Parameters(
                        "clear", "boolean",
                        """
                        是否清空该执行体已积累的执行记录（消息树与专属执行方案）后重头开始，默认 false。\
                        适用于上一轮执行跑偏、上下文已污染，或想换一个全新任务方向时；\
                        为 true 时会先清理当前进度，再按本次 target 重新走一遍完整流程。"""));
    }

    // ==================== extension 读取 ====================

    /** 从 extension 里取 model key（去首尾空白）；缺失或为 null 时返回 null */
    private String extractModelKey(SubAgentComponent.SubAgentArguments arguments) {
        Object raw = arguments.getExtension() == null ? null : arguments.getExtension().get("model");
        return raw == null ? null : String.valueOf(raw).trim();
    }

    /** 读取 extension 里的 clear 开关。缺失、null 或非 true 均视为 false——是否重开由主 Agent 显式声明 */
    private boolean readClear(Map<String, Object> extension) {
        if (extension == null) {
            return false;
        }
        Object value = extension.get("clear");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
