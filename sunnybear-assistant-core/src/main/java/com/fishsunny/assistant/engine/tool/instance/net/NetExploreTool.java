package com.fishsunny.assistant.engine.tool.instance.net;

/*
 * @Usage 网络探索子 Agent - 接受收集目标，联网搜索并阅读网页，收集完成后输出结构化报告
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/24
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.NetToolKit;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.util.*;

@ToolKitComponent(NetToolKit.class)
@ConditionalOnExpression("${engine.tool.net.enable:true} && ${engine.tool.net.net-explore.enable:true}")
public class NetExploreTool implements ToolHandler {

    public static final String NAME = "net_explore_tool";

    private static final Logger log = LoggerFactory.getLogger(NetExploreTool.class);

    /** 子 Agent 可用的网络工具（按 handler 名称过滤，避免递归到自己） */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            MetaSOAISearchTool.NAME,   // web_search_tool
            WebReaderTool.NAME         // web_reader_tool
    );

    /** 单条工具结果最大保留长度 */
    private static final int MAX_RESULT_LENGTH = 5000;

    /** 附录（工具调用明细）总长度上限 */
    private static final int APPENDIX_MAX_LENGTH = 10000;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;

    public NetExploreTool(ObjectMapper objectMapper,
                          @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                          ToolCallLoop toolCallLoop,
                          @Lazy ToolExecutor toolExecutor) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.toolCallLoop = toolCallLoop;
        this.toolExecutor = toolExecutor;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        联网探索信息的子 Agent。接受一个收集目标，自动搜索、阅读网页、评估结果，\
                        最终返回一份结构化的收集报告。适合需要深度调研某个主题的场景。""")
                .setRequired(List.of("target"));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("收集目标，描述你需要收集什么信息。例如'AI 安全的最新进展'、'微服务架构最佳实践'");

        register.setParameters(List.of(targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null) {
                throw new ToolExecutor.ToolExecuteException("参数为空");
            }
            if (!StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        try {
            // ========== 收集器与统计 ==========
            StringBuilder collector = new StringBuilder();
            int[] usefulRounds = {0};
            int[] discardedRounds = {0};

            // ========== 工具结果钩子 ==========
            ToolCallLoop.ToolResultHook hook = (roundResults, aiText) -> {
                boolean keep = isTrueResponse(aiText);

                if (keep) {
                    usefulRounds[0]++;
                    for (ToolCallLoop.RoundResult r : roundResults) {
                        collector.append("### ").append(r.toolName()).append("\n");
                        collector.append("**参数:** ").append(truncate(r.arguments(), 300)).append("\n\n");
                        collector.append(truncate(r.result(), MAX_RESULT_LENGTH)).append("\n\n---\n\n");
                    }
                } else {
                    discardedRounds[0]++;
                }
                return true; // 始终继续循环，由 AI 决定何时停止
            };

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
            String finalReport = toolCallLoop.execute(missionAISettings, request, context,
                    new ToolCallLoop.AgentLoopHook(null, hook));

            // ========== 组装返回结果 ==========
            return assembleResponse(finalReport, collector.toString(), usefulRounds[0], discardedRounds[0]);

        } catch (Exception e) {
            log.error("NetExploreTool 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("网络探索子 Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * 组装最终返回结果：AI 最终报告 + 附录（工具调用明细，单独长度保护后拼接到末尾）。
     */
    private ToolExecutor.ToolExecuteResponse assembleResponse(String finalReport, String rawData,
                                                               int usefulRounds, int discardedRounds) {
        StringBuilder result = new StringBuilder();

        result.append(finalReport.trim());

        // --- 附录：对原始收集数据单独截断，再拼接到末尾 ---
        if (StringUtils.hasText(rawData)) {
            if (!result.isEmpty()) {
                result.append("\n\n---\n");
            }
            result.append("## 📎 附录：工具调用明细\n");
            result.append("> 有效轮次: ").append(usefulRounds)
                    .append(" | 丢弃轮次: ").append(discardedRounds).append("\n\n");

            // 附录单独长度保护：截断后再拼入
            String truncatedAppendix = truncate(rawData, APPENDIX_MAX_LENGTH);
            result.append(truncatedAppendix);
        }

        return new ToolExecutor.ToolExecuteResponse(name(), result.toString());
    }

    /**
     * 判断 AI 本轮输出是否表示"保留"。
     * 支持中文"是/保留/有效"和英文 true/keep 等多种表达。
     */
    private boolean isTrueResponse(String aiText) {
        if (aiText == null) return true; // 无文本默认保留
        String lower = aiText.trim().toLowerCase();

        // 明确否定词优先（避免 "false" 劣后于 "true" 的子串匹配）
        if (lower.contains("false") || lower.contains("丢弃") || lower.contains("无效")
                || lower.contains("不保留") || lower.contains("no")) {
            return false;
        }
        // 明确肯定词
        if (lower.contains("true") || lower.contains("保留") || lower.contains("有效")
                || lower.contains("有价值") || lower.contains("yes") || lower.contains("继续")) {
            return true;
        }
        // 默认：有工具调用但无明确评价 → 保守保留
        return true;
    }

    /**
     * 截断过长文本，保留首部。
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n\n... (内容过长，已截断 " + (text.length() - maxLen) + " 字符)";
    }

    // ==================== 提示词 ====================

    /**
     * 子 Agent 系统提示词。
     * AI 负责：调度工具 → 评估每轮进展 → 最终输出结构化收集报告。
     */
    private String buildSystemPrompt() {
        return """
                你是一个网络信息检索助手。你的职责是调用工具收集信息，并最终输出一份完整的收集报告。

                ## 可用工具
                - **web_search_tool** — 搜索互联网，获取网页列表和摘要
                - **web_reader_tool** — 阅读指定网页的详细内容

                ## 每轮工作规则（必须严格遵守）
                1. 在每一轮中，先**简短评估当前进展**，然后调用一个或多个工具：
                   - 进展顺利 → 输出 `true — {简短说明}`（例如 "true — 找到相关文章，深入阅读"）
                   - 无价值发现 → 输出 `false — {简短说明}`（例如 "false — 搜索结果不相关，换个关键词"）
                   - 说明不超过 20 字
                2. 说明后立即调用工具，不要在中间输出长篇分析
                3. 如果上一轮结果不理想，**换策略**：换关键词、换搜索角度、换来源类型

                ## 收集策略
                - 从**多个角度、多个关键词**搜索，确保覆盖面
                - 对高质量来源（官方文档、权威媒体、学术论文）优先深入阅读
                - 遇到矛盾信息时交叉验证
                - 连续两轮无有价值发现时，停止收集

                ## 最终报告格式
                当你认为信息已收集充分时，**停止调用工具**，输出最终报告：

                # 收集报告：{一句话概括目标}

                ## 收集概况
                - 从哪些角度进行了搜索
                - 阅读了多少个页面
                - 信息质量总体评估

                ## 关键发现
                ### 1. {发现标题}
                {详细描述，引用具体来源}

                ### 2. {发现标题}
                ...

                ## 信息来源
                - [{来源标题}](URL) — {简短说明}
                ...

                ## 信息评估
                - **可信度**：高 / 中 / 低（说明原因）
                - **完整度**：高 / 中 / 低（说明是否还有未覆盖的方面）
                - **时效性**：信息的新旧程度

                ## 严禁行为
                - 禁止编造未收集到的信息
                - 禁止在非最终轮输出完整报告
                - 禁止凭空猜测来源 URL
                - 报告中的每一条信息都必须来自工具返回的实际内容""";
    }

    private String buildUserPrompt(String target) {
        return """
                [收集目标]
                %s

                开始检索。按规则逐步收集，信息充足后输出最终报告。""".formatted(target);
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
