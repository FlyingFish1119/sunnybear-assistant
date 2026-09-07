package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 文件探索子 Agent - 接受一个目标目录，自动列目录、内容搜索、读取文件，完成后输出结构化探索报告。
 *        作为 SubAgentToolHandler 由 agent_tool 路由调用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/7
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.engine.tool.instance.file.FileListTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileReadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileSearchTool;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;

@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${engine.tool.agent.file-explore.enable:true}")
public class FileExploreTool implements SubAgentToolHandler {

    public static final String NAME = "file_explore_tool";

    private static final Logger log = LoggerFactory.getLogger(FileExploreTool.class);

    /** 子 Agent 可用的本地文件工具（按 handler 名称过滤，避免递归到自己） */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            FileListTool.NAME,    // file_list_tool
            FileSearchTool.NAME,  // file_search_tool
            FileReadTool.NAME     // file_read_tool
    );

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;
    private final SecurityService securityService;

    public FileExploreTool(ObjectMapper objectMapper,
                           @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                           ToolCallLoop toolCallLoop,
                           SecurityService securityService,
                           @Lazy ToolExecutor toolExecutor) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.toolCallLoop = toolCallLoop;
        this.securityService = securityService;
        this.toolExecutor = toolExecutor;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        探索本地目录的子 Agent。接受一个目标目录，自动浏览目录结构、搜索文件内容、阅读关键文件，\
                        最终返回一份结构化的探索报告。适合需要快速了解某个项目/目录结构或定位某段实现逻辑的场景。""")
                .setRequired(List.of("target"));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters(
                "target", "string",
                "要探索的目标目录路径（可顺带说明想弄清的问题），例如 /home/user/project，或“梳理 D:/proj 里订单模块的实现”");

        register.setParameters(List.of(targetParam));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session"}, type = {ChatSession.class, WebSocketSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null) {
                throw new ToolExecutor.ToolExecuteException("参数为空");
            }
            if (!StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空，需指定要探索的目标目录");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        try {
            // ========== 确认机制（无审查/定时任务由 SecurityService 统一裁决） ==========
            ask(context, arguments.getTarget());

            // ========== 构建请求 ==========
            List<StandardToolRegister> subAgentTools = StandardToolRegister.buildToolRegisterByHandlers(
                    toolExecutor, SUB_AGENT_TOOLS);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage().system(buildSystemPrompt()));
            messages.add(new ChatMessage().user(buildUserPrompt(arguments.getTarget())));

            ChatRequest request = new ChatRequest()
                    .loadSettings(missionAISettings)
                    .setMessages(messages)
                    .setTools(subAgentTools);

            // ========== 执行循环，捕获 AI 的最终报告 ==========
            String finalReport = toolCallLoop.execute(missionAISettings, request, context, null);

            // ========== 组装返回结果 ==========
            return new ToolExecutor.ToolExecuteResponse(name(), finalReport);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("FileExploreTool 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("文件探索子 Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * 向用户发送确认请求并等待响应。
     */
    private void ask(Map<String, Object> context, String target) throws Exception {
        String message = "### 目录探索请求\n\n"
                + "AI 请求对本地目录进行探索，将自动浏览目录结构、搜索文件内容并读取文件。\n\n"
                + "| 属性 | 内容 |\n"
                + "|------|------|\n"
                + "| 目标目录 | **" + target + "** |\n"
                + "| 可用工具 | file_list_tool（浏览目录）、file_search_tool（内容搜索）、file_read_tool（读取文件） |\n"
                + "| 模式 | 深度探索（子 Agent 循环，仅只读、不改动文件） |\n\n"
                + "> ⚠️ 子 Agent 将自动多轮读取本地文件并进行分析，可能消耗较多 token。请确认目标合理后再允许执行。";
        securityService.ask(NAME, message, 60, context);
    }

    // ==================== 提示词 ====================

    /**
     * 子 Agent 系统提示词。
     * AI 负责：调度工具 → 评估每轮进展 → 最终输出结构化探索报告。
     */
    private String buildSystemPrompt() {
        return """
                你是一个本地文件探索助手。你的职责是围绕给定的目标目录，调用工具了解其结构、定位并精读关键内容，\
                最终输出一份完整的探索报告。

                ## 可用工具
                - **file_list_tool** — 浏览目录，列出文件和子目录（递归、glob 过滤、分页）
                - **file_search_tool** — 在目录内按内容搜索文件（支持关键词/正则、glob 过滤、上下文行）
                - **file_read_tool** — 读取文件内容（支持多文件与行范围）

                ## 每轮工作规则（必须严格遵守）
                1. 在每一轮中，先**简短评估当前进展**（不超过 20 字），然后调用一个或多个工具。
                2. 说明后立即调用工具，不要在中间输出长篇分析。
                3. 如果上一轮结果不理想，**换策略**：换搜索关键词、换子目录、换过滤条件。
                4. 前面各轮的工具结果都在对话上下文中，下一轮可直接看到；最终报告由你自行汇总要点，**不会自动附加工具调用明细附录**。

                ## 探索策略
                - **只围绕 target 指定的目标目录活动**，不要漫游到无关的系统目录
                - 先用 file_list_tool 从 depth=1 开始逐层了解目录结构（不要一次深度拉满大目录）
                - 需要在大量文件里定位内容时，用 file_search_tool 搜关键词/正则，比逐目录翻快得多
                - 定位到候选文件后用 file_read_tool 精读（大文件可指定行范围，避免整段贴出）
                - 目录或文件不存在/不可读时，如实说明并停止该分支，**禁止臆造内容或路径**
                - 不要在找不到有价值信息时无限加深度：多轮仍无发现即可停止

                ## 最终报告格式
                当你认为信息已收集充分时，**停止调用工具**，输出最终报告：

                # 目录探索报告：{目标目录}

                ## 结构概览
                - 目录布局 / 主要模块划分
                - 关键入口与核心文件

                ## 关键发现
                ### 1. {发现标题}
                {详细描述，引用具体文件路径与行号}

                ### 2. {发现标题}
                ...

                ## 引用文件
                - {文件路径} — {一句话说明，可含行号}

                ## 探索范围与限制
                - 覆盖了哪些子目录 / 哪些未深入
                - 遗留问题（若目标问题未完全回答，说明还差什么）

                ## 严禁行为
                - 禁止编造未读到的文件内容
                - 禁止在非最终轮输出完整报告
                - 禁止读取目标目录之外、与探索目标无关的敏感系统文件
                - 报告中的每一条信息都必须来自工具返回的实际内容""";
    }

    private String buildUserPrompt(String target) {
        return """
                [目标目录]
                %s

                开始探索。以该目录为根，先了解结构，再定位并精读相关文件，信息充足后输出最终报告。""".formatted(target);
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
