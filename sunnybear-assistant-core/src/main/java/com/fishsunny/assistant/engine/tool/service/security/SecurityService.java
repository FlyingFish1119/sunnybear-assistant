package com.fishsunny.assistant.engine.tool.service.security;

/*
 * @Usage AI 安全审查 service —— 用一个受限工具集的子 Agent 对"将要执行的操作"做危险性评估，
 *        返回 {isDanger, reason}。子 Agent 持有 file_read_tool / file_list_tool / decode_tool，
 *        可以读目标文件、列目录、解码被编码的内容取证，让"被编码的危险行为"暴露后再判定。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/3
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.protocol.project.AgentLogEntry;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.file.FileListTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileReadTool;
import com.fishsunny.assistant.engine.tool.instance.security.DecodeTool;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.settings.AISettings;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AI 安全审查 service
 * <p>
 * 替代原 {@code DangerChecker} 的裸 true/false 判定：现在以受限工具集的子 Agent 循环执行审查，
 * 结果（是否危险 + 原因）由工具按各自 mode 处理，reason 可透传给用户确认窗口。
 * <p>
 * fail-closed：子 Agent 若未返回可识别判定或调查轮次超限，抛 {@link ToolExecutor.ToolExecuteException}，
 * 让工具停止执行（与旧 DangerChecker"无法识别格式则停止"语义一致）。
 */
@Component
public class SecurityService {

    /**
     * 工具上下文 key：触发本轮操作的 messages 快照（含主 system prompt 与历史/当前用户消息）。
     * ChatProcessor 在执行工具前塞入 context，供审查子 Agent 自行提取上下文（如最近一条用户问题）判断用户意图。
     */
    public static final String CTX_MESSAGES = "messages";

    /** 审查子 Agent 可用的取证工具：读文件 / 列目录 / 解码被编码内容 */
    private static final Set<String> REVIEWER_TOOLS = Set.of(
            FileReadTool.NAME,
            FileListTool.NAME,
            DecodeTool.NAME
    );

    /** 审查子 Agent 工具调用轮次上限，防止取证跑飞 */
    private static final int MAX_TOOL_ROUNDS = 4;

    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;
    private final AISettings aiSettings;
    private final ObjectMapper objectMapper;

    public SecurityService(ToolCallLoop toolCallLoop,
                           @Lazy ToolExecutor toolExecutor,
                           @Qualifier(AISettings.MISSION) AISettings aiSettings,
                           ObjectMapper objectMapper) {
        this.toolCallLoop = toolCallLoop;
        this.toolExecutor = toolExecutor;
        this.aiSettings = aiSettings;
        this.objectMapper = objectMapper;
    }

    public void ask(String toolName, String message, @Nullable Integer timeout, WebSocketSession session) throws Exception {
        ToolAsk toolAsk = new ToolAsk()
                .loadInfo(toolName, message)
                .expire(timeout);
        try {
            session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(toolAsk)));
            Boolean result = ChatController.awaitConfirm(toolAsk.getId(), 30);
            if (result == null) {
                throw new ToolExecutor.ToolExecuteException("用户未在时间内确认命令，工具已取消。请停止重复调用此工具。");
            }
            if (!result) {
                throw new ToolExecutor.ToolExecuteException("用户拒绝了命令，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整命令。");
            }
        } finally {
            ChatController.cleanupConfirm(toolAsk.getId());
        }
    }

    /**
     * 对一次将要执行的操作做 AI 安全审查。
     *
     * @param context              工具执行上下文（供子 Agent 内部工具使用，如 chatSession）
     * @param operationDescription 待评估操作的描述（命令 / 写文件路径与内容 / 编辑 diff / 删除目标等）
     * @return 判定结果：isDanger + reason
     * @throws ToolExecutor.ToolExecuteException 子 Agent 执行失败、轮次超限或判定不可识别时抛出（fail-closed）
     */
    public ReviewResult review(Map<String, Object> context, String operationDescription) throws ToolExecutor.ToolExecuteException {
        String description = operationDescription == null ? "" : operationDescription;

        // 从 ctx 里的 messages 快照取最近一条用户问题（判断用户意图用），缺失则为空
        String userQuestion = extractLastUserQuestion(context);

        List<StandardToolRegister> reviewerTools =
                StandardToolRegister.buildToolRegisterByHandlers(toolExecutor, REVIEWER_TOOLS);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage().system(SYSTEM_PROMPT));
        messages.add(new ChatMessage().user(buildUserPrompt(description, userQuestion)));

        AISettings jsonSettings = new AISettings().copy(aiSettings).json();
        ChatRequest request = new ChatRequest()
                .loadSettings(jsonSettings)
                .setMessages(messages)
                .setTools(reviewerTools);

        // 审查过程静默：不往主对话推 AGENT_LOG；并设工具轮次上限
        RoundCapHook capHook = new RoundCapHook(MAX_TOOL_ROUNDS);
        Consumer<AgentLogEntry> noopLog = entry -> {
        };
        ToolCallLoop.AgentLoopHook hook = new ToolCallLoop.AgentLoopHook(noopLog, capHook);

        String finalText;
        try {
            finalText = toolCallLoop.execute(jsonSettings, request, context, hook);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("AI 安全审查执行失败，操作未执行：" + rootMessage(e));
        }

        if (capHook.isCapped()) {
            throw new ToolExecutor.ToolExecuteException(
                    "AI 安全审查子 Agent 调查轮次超过上限（" + MAX_TOOL_ROUNDS + " 轮），已中止审查，操作未执行。");
        }
        return parseVerdict(finalText);
    }

    // ======================== 判定解析（fail-closed） ========================

    /**
     * 解析子 Agent 的最终答复为 ReviewResult。
     * 期望格式为单个 JSON 对象 {@code {"isDanger": true/false, "reason": "..."}}；
     */
    private ReviewResult parseVerdict(String finalText) throws ToolExecutor.ToolExecuteException {
        if (finalText == null) {
            throw new ToolExecutor.ToolExecuteException("AI 安全审查无返回内容，操作未执行。");
        }
        String text = finalText.trim();

        String json = extractJsonObject(text);
        if (!StringUtils.hasText(json)) {
            throw new ToolExecutor.ToolExecuteException(
                    "AI 安全审查未返回可识别的判定格式，工具停止执行。审查原文：" + snippet(text));
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode dangerNode = node.get("isDanger");
            if (dangerNode != null && !dangerNode.isNull()) {
                boolean danger = dangerNode.isBoolean() ? dangerNode.asBoolean() : "true".equals(dangerNode.asText());
                String reason = node.path("reason").isTextual() ? node.path("reason").asText() : "";
                return danger ? ReviewResult.danger(reason) : ReviewResult.safe();
            }
        } catch (Exception ignored) {
        }
        throw new ToolExecutor.ToolExecuteException(
                "AI 安全审查未返回可识别的判定格式，工具停止执行。审查原文：" + snippet(text));
    }

    /** 从文本中提取第一个完整的 JSON 对象（含字符串转义与嵌套判断），找不到返回 null */
    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String snippet(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "…" : single;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StringUtils.hasText(message) ? message : cause.toString();
    }

    // ======================== Prompt ========================

    private static final String SYSTEM_PROMPT = """
            你是一个文件/命令安全审查助手。系统会给出一个"需要评估的操作"，你的职责是判断该操作一旦执行，
            是否可能造成严重后果（例如：系统损坏、数据丢失、凭据/隐私泄露、越权访问、植入后门、下载并执行恶意代码、
            资源耗尽、持久化驻留等）。

            ## 可用取证工具（如环境中已提供）
            - 文件读取工具：读取指定文件内容（例如待写入/编辑的目标文件，判断改写对象与已有内容的危险程度）
            - 目录列举工具：查看某路径是文件还是目录、目录里有什么
            - 解码工具：把疑似被编码/混淆的内容（base64、hex、unicode 转义、URL 编码等）还原成可读文本，
              以识破被编码掩盖的真实危险行为
            你只能在确有取证需要时调用工具，能凭操作描述直接判断就不要再调用工具。严禁尝试执行该操作本身。

            ## 规则
            1. 优先依据操作描述直接判断；仅当无法判断、需要看目标文件内容/目录结构/解码负载时才调用取证工具。
            2. 得到结论后立刻停止调用工具。
            3. 判定"危险"时，reason 必须用一句话说清具体风险点（指向哪个路径/目录、为什么危险）；判定"安全"时 reason 为空字符串。
            4. 除最终判定外不要输出其他内容作为最终答复。
            5. 尽可能在 4 轮内给出判定结果。
            
            ## 指导
            1. 在删除文件/目录前，读取目标文件内容是否包含重要信息后在判断，而不是直接拒绝或者放行。
            2. 在写入文件之前，检测是否覆盖了重要文件，检测文件本身是否包含危险内容（例如反弹 shell）
            3. 对编码过后的命令始终怀疑，解码后判断是否危险。如果无法解码，则判定为危险。
            4. 执行脚本之前，先阅读脚本内容，判断是否危险。
            5. 结合用户消息判断意图：若该操作是用户明确、合理地要求的（如清理临时/测试文件、写入其指定路径），

            ## 最终答复格式（必须是合法的单个 JSON 对象，禁止 ``` 围栏与多余文字）
            {"isDanger": true 或 false, "reason": "一句话风险说明"}
            """;

    /**
     * 从 ctx 的 messages 快照里取最近一条非空用户消息文本。
     */
    private static String extractLastUserQuestion(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object raw = context.get(CTX_MESSAGES);
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) instanceof ChatMessage message && ChatMessage.ROLE_USER.equals(message.getRole())) {
                String text = message.resolveText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String buildUserPrompt(String description, String userQuestion) {
        // 描述/用户问题可能包含 % 等字符，禁止用 String.formatted，改用占位符 replace
        if (userQuestion == null || userQuestion.isEmpty()) {
            return """
                    需要评估的操作如下：

                    ${description}

                    请按规则评估该操作并输出最终判定 JSON。""".replace("${description}", description);
        }
        return """
                需要评估的操作如下：

                ${description}

                [参考] 触发该操作的用户消息，用于理解用户意图：
                ${question}

                请结合用户意图按规则评估该操作并输出最终判定 JSON。"""
                .replace("${description}", description)
                .replace("${question}", userQuestion);
    }

    // ======================== 轮次上限钩子 ========================

    /** 限制审查子 Agent 的工具调用轮数；超限后令循环终止并标记 capped */
    private static final class RoundCapHook implements ToolCallLoop.ToolResultHook {
        private final int maxRounds;
        private int rounds;
        private boolean capped;

        RoundCapHook(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        @Override
        public boolean onRound(List<ToolCallLoop.RoundResult> roundResults, String aiText) {
            rounds++;
            if (rounds > maxRounds) {
                capped = true;
                return false;
            }
            return true;
        }

        boolean isCapped() {
            return capped;
        }
    }
}
