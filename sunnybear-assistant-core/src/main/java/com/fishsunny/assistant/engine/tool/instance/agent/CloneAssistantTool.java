package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 克隆助手子 Agent —— 让主 Agent 与「另一个自己」商量。
 *        克隆体与主 Agent 同人格（复用 chat 提示词）、同工具集，并持有一棵落在会话目录下的持久消息树：
 *        每次召唤都往这棵树里追加一轮，所以这一次的克隆体读得到上一次的自己的完整推理。
 *        由 agent_tool 路由调用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/11
 */

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
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

@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${engine.tool.agent.clone-assistant.enable:true}")
public class CloneAssistantTool implements SubAgentToolHandler {

    public static final String NAME = "clone_assistant_tool";

    private static final Logger log = LoggerFactory.getLogger(CloneAssistantTool.class);

    /** 消息树文件名，落在会话的 file 目录下 */
    private static final String MESSAGE_TREE_FILE = "clone-assistant-message-tree.json";

    private final ObjectMapper objectMapper;
    private final AISettings chatAISettings;
    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;
    private final SessionFileManager sessionFileManager;
    private final ToolRegister register;
    private final Map<String, ToolHandler> registry;

    public CloneAssistantTool(ObjectMapper objectMapper,
                              @Lazy ToolExecutor toolExecutor,
                              ToolCallLoop toolCallLoop,
                              SessionFileManager sessionFileManager,
                              @Qualifier(AISettings.CHAT) AISettings chatAISettings,
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
        this.toolExecutor = toolExecutor;
        this.toolCallLoop = toolCallLoop;
        this.sessionFileManager = sessionFileManager;
        this.chatAISettings = chatAISettings;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        和另一个自己商量。把拿不准的事原样抛给「另一个你」——同一个人格、同一套工具、同一份记忆，\
                        但它不在这件事里，能冷静地替你重想一遍，也敢直接反驳你。\
                        它记得你们之前每一次商量的全过程，所以可以接着上次继续往下推。返回它这轮的判断和理由。
                        适合：方案取舍、要不要做某件事、对自己的判断心里没底、想被认真反驳一次。\
                        不适合：查得到的事实（直接调工具更快）、一句话能定的小事。""")
                .setRequired(List.of("target"))
                .setParameters(List.of(new ToolRegister.Parameters(
                        "target", "string",
                        """
                        你要商量的事，像对另一个自己说话那样直接写出来：在纠结什么、倾向哪边、担心什么。\
                        例如“订单服务要不要现在拆出去？我倾向拆，但担心分布式事务撑不住，你说呢”。\
                        对方看得到你们之前的商量记录，不用重新交代背景。""")));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session", "chatRequest"}, type = {ChatSession.class, WebSocketSession.class, ChatRequest.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null || !StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空，需说明要商量的问题");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        ChatRequest callerRequest = (ChatRequest) context.get("chatRequest");

        try {
            // 1. 读树：首次召唤时文件还不存在，视为空树
            Path treeFile = sessionFileManager.prepareSessionFile(chatSession.getId(), MESSAGE_TREE_FILE);
            List<ChatMessage> tree = readMessageTree(treeFile);

            // 2. 本轮问题追加进树，成为克隆体看到的最后一条 user
            tree.add(new ChatMessage().user(arguments.getTarget()));
            int treeSize = tree.size();

            // 3. 组装请求：system + 整棵树。
            //    不需要在此展开文件引用——ToolCallLoop 每轮 translate 前会统一展开一遍，
            //    而树里的消息和 request 里的是同一批对象，展开结果会随第 5 步一起落盘。
            List<ChatMessage> sendMessages = new ArrayList<>();
            sendMessages.add(new ChatMessage().system(buildSystemPrompt(callerRequest)));
            sendMessages.addAll(tree);

            AISettings settings = new AISettings().copy(chatAISettings).setStream(false);
            ChatRequest request = new ChatRequest()
                    .loadSettings(settings)
                    .setMessages(sendMessages)
                    .setTools(buildToolRegisters());

            // 4. 跑循环：克隆体自己调工具、自己推敲，中间产生的 assistant / tool 消息由 ToolCallLoop 追加在 sendMessages 尾部
            String finalText = toolCallLoop.execute(settings, request, context);

            // 5. 把本轮新增的消息（克隆体的答复、它自己的工具调用与结果）接回树并落盘。
            //    tree 与 sendMessages 里的消息是同一批对象，循环内每轮开头的展开都已在其中生效
            tree.addAll(sendMessages.subList(1 + treeSize, sendMessages.size()));
            writeMessageTree(tree, treeFile);

            return new ToolExecutor.ToolExecuteResponse(name(), finalText);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("克隆助手子 Agent 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("克隆助手子 Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * 克隆体这次能用的工具表：主 Agent 那套（全量减 ChatProcessor.EXCLUDE_TOOLS），
     * 再把 agent_tool 里"能召唤哪些子 Agent"的说明换成去掉克隆体自己的版本。
     * <p>
     * 为什么要换：AgentTool 那份说明是它构造时按全部子 Agent 拼的、全局就一份，里面带着
     * clone_assistant_tool——直接给克隆体，等于告诉它"你可以召唤你自己"。
     * 子 Agent 互相召唤是允许的（file_explore、net_explore 都该留着），只有自己不能再套自己。
     * <p>
     * 两处都要换：function 描述和 agent 参数的描述各拼了一份名字表，漏一处照样广告出去。
     * <p>
     * 改这份不会污染别人：buildToolRegisterExcluding 每次调用都现造一批新的
     * StandardToolRegister（连同 parameters.properties 里那些对象），不是共享的注册对象。
     */
    List<StandardToolRegister> buildToolRegisters() throws ToolExecutor.ToolExecuteException {
        List<StandardToolRegister> toolRegisters = StandardToolRegister
                .buildToolRegisterExcluding(toolExecutor, ChatProcessor.getEXCLUDE_TOOLS());
        StandardToolRegister agentToolRegister = toolRegisters.stream()
                .filter(tool -> tool.getFunction().getName().equals(AgentTool.NAME))
                .findFirst()
                .orElseThrow(() -> new ToolExecutor.ToolExecuteException("工具注册表中不存在 " + AgentTool.NAME + "，无法为克隆体裁剪子 Agent 说明"));

        agentToolRegister.getFunction().setDescription(AgentToolKit.getSubDescription(registry));
        agentToolRegister.getFunction().getParameters()
                .getProperties().get(AgentTool.PARAM_AGENT)
                .setDescription(AgentToolKit.getAgentParamDescription(registry));
        return toolRegisters;
    }

    /**
     * 克隆体的系统提示词 = 调用方这次实际在用的系统提示词 + 一段场景说明。
     * <p>
     * 必须从 callerRequest 里取，不能用 chatAISettings.getPrompt()：角色会话等插件是用
     * ChatProvider.getSystemProvider() 整个重写系统提示词的（角色人格、世界观、词条表…），
     * chat 配置里那份跟实际发出去的完全不是一回事。而且调用方那份已经做完变量替换、
     * 知识库与记忆注入，直接拿来即可。
     */
    private String buildSystemPrompt(ChatRequest callerRequest) {
        String prompt = callerRequest == null ? null : extractSystemPrompt(callerRequest.getMessages());
        if (!StringUtils.hasText(prompt)) {
            // 走到这说明调用方压根没塞 system 消息，退回 chat 配置的提示词，至少别让克隆体裸奔
            log.warn("调用方 ChatRequest 中没有 system 消息，克隆助手回退到 chat 配置的提示词");
            prompt = chatAISettings.getPrompt();
        }
        return prompt + CLONE_SCENE_PROMPT;
    }

    /**
     * 拼接消息列表里所有 system 消息的文本，没有则返回 null。
     * 与 AnthropicBaseAIAdapter.extractSystemPrompt 同一套语义（那边是 protected，够不着）。
     */
    private String extractSystemPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (!ChatMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            String text = message.resolveText();
            if (StringUtils.hasText(text)) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(text);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 附在系统提示词末尾的场景说明。
     * <p>
     * 上面那段是给人设用的，讲的是"怎么面对用户"；而这里根本没用户，所以必须把场景重新交代清楚，
     * 否则克隆体会把对面当成用户，输出「你好，请问需要我做什么」这类话，也意识不到自己可以反对对方。
     */
    private static final String CLONE_SCENE_PROMPT = """


            ## 当前场景：对面是「另一个你」，不是用户

            你不是在接待谁。上面那套人设是你在用户面前的样子，而此刻没有用户——
            对面是同一个你的另一个实例。它在外面做事，遇到拿不准的地方，就把问题原样抛进来找你。

            所以：

            - **不要用面对用户的口吻。** 不要问候、不要"请问需要我做什么"、不要复述你能干什么、
              不要反问对方想让你做什么——它已经把问题给你了，直接回答。
            - **平视对方。** 它是在问你的意见，不是给你派活。你可以不同意它，也可以推翻自己上一轮说过的话。
              它要是写明了自己的倾向，先想清楚那个倾向哪里成立、哪里不成立，别顺着往下滑。
            - **上文是你们俩的商量记录。** 里面每一轮的推理、以及你自己查过的东西都在，可以引用、可以改口。
              但这轮问的事要是和之前无关，就当成新问题从头想，别硬往旧的结论上接。
            - **该查就去查。** 需要事实支撑时用工具查证，不要凭印象下结论。
            - **收在结论上。** 先给判断，再给理由，最后用一两句话讲清楚，让对方拿着就能拍板。
              这是你们俩之间的对话，不是交付给用户的报告，不用堆标题和排版。""";

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
            throw new RuntimeException("克隆助手消息树解析失败，请检查或删除该文件后重试: " + treeFile.toAbsolutePath()
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
