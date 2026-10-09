package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 计算机操控子 Agent（Computer Use）—— 主 Agent 把一件"在本机屏幕上完成的事"派进来，
 *        它以 Operator 视觉模型为大脑，先截屏观察当前界面，再提交桌面操作链，操作后截屏确认，
 *        如此循环推进，直到目标达成或确认受阻，最后返回执行结果。
 *        拥有 bot_chain_tool（桌面操作链）与 screen_capture_tool（截屏）两个工具。
 *        每次调用独立跑一段（不跨轮保留执行记录），由 agent_tool 路由调用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/9
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.utils.EasyReActProcessor;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.engine.tool.instance.bot.BotChainTool;
import com.fishsunny.assistant.engine.tool.instance.view.ScreenCaptureTool;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计算机操控子 Agent。
 * <p>
 * 面向 Computer Use 场景：需要在本机桌面上通过鼠标、键盘真实操作来完成的图形界面任务。
 * 以 Operator 模型（视觉 + 交互能力强）做决策，工具集只开放桌面操作与截屏两项。
 */
@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${engine.tool.agent.computer-use.enable:true}")
public class ComputerUseTool implements SubAgentToolHandler {

    public static final String NAME = "computer_use_tool";

    private static final Logger log = LoggerFactory.getLogger(ComputerUseTool.class);

    /** 子 Agent 可用的桌面操控工具（按 handler 名称过滤，避免递归到自己） */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            ScreenCaptureTool.NAME,  // screen_capture_tool
            BotChainTool.NAME        // bot_chain_tool
    );

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings operatorAISettings;
    private final EasyReActProcessor easyReActProcessor;
    private final ToolExecutor toolExecutor;
    private final SecurityService securityService;

    public ComputerUseTool(ObjectMapper objectMapper,
                           @Qualifier(AISettings.OPERATOR) AISettings operatorAISettings,
                           EasyReActProcessor easyReActProcessor,
                           SecurityService securityService,
                           @Lazy ToolExecutor toolExecutor) {
        this.objectMapper = objectMapper;
        this.operatorAISettings = operatorAISettings;
        this.easyReActProcessor = easyReActProcessor;
        this.securityService = securityService;
        this.toolExecutor = toolExecutor;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        计算机操控子 Agent。把一件需要在本机桌面上完成的事交给它：它先截屏看清当前界面，\
                        再用鼠标、键盘真实操作，操作后截屏确认，如此循环推进直到完成，最后返回执行结果。\
                        适合：图形界面里点按输入、拖拽、多步软件操作、按视觉目标定位后操作等需要「看屏幕并动手」的任务。\
                        不适合：纯文本/文件/联网检索类任务，以及必须在主对话里先与用户对齐的判断。""")
                .setRequired(List.of("target"));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("要完成的桌面任务，交代清楚目标与验收标准。例如：'打开记事本输入“今晚加班”并保存到桌面为 todo.txt'、"
                        + "'在浏览器里搜索今天的天气并告诉我结果'。越具体，它越能准确规划操作步骤。");

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
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空，需说明要完成的桌面任务");
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
                    .loadSettings(operatorAISettings)
                    .setMessages(messages)
                    .setTools(subAgentTools);

            // ========== 执行循环，捕获 AI 的最终答复 ==========
            String finalReport = easyReActProcessor.execute(operatorAISettings, request, context, null);

            return new ToolExecutor.ToolExecuteResponse(name(), finalReport);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("ComputerUseTool 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("计算机操控子 Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * 向用户发送确认请求并等待响应。接管鼠标键盘是有副作用的操作，执行前必须经用户放行。
     */
    private void ask(Map<String, Object> context, String target) throws Exception {
        String message = "### 计算机操控请求\n\n"
                + "AI 请求接管本机鼠标与键盘，在桌面上替你完成一项操作。\n\n"
                + "| 属性 | 内容 |\n"
                + "|------|------|\n"
                + "| 任务目标 | **" + target + "** |\n"
                + "| 可用工具 | screen_capture_tool（截屏）、bot_chain_tool（鼠标/键盘操作链） |\n"
                + "| 模式 | 视觉驱动（每步截屏 → 操作 → 确认，循环推进） |\n\n"
                + "> ⚠️ 子 Agent 将真实操作你的鼠标与键盘，可能持续多轮并消耗较多 token。请确认任务目标后再允许执行。";
        securityService.ask(NAME, message, 60, context);
    }

    // ==================== 提示词 ====================

    /**
     * 子 Agent 系统提示词。
     * AI 负责：截屏观察 → 规划动作 → 提交操作链 → 截屏确认 → 循环推进 → 输出结构化执行结果。
     */
    private String buildSystemPrompt() {
        return """
                你是一个计算机操控助手（Computer Use）。你的职责是围绕给定任务，在本机桌面上通过真实的鼠标、\
                键盘操作把它做完，并如实汇报结果。你能看到屏幕截图，也会动手操作。

                ## 可用工具
                - **screen_capture_tool** — 截取当前屏幕。
                  - captureType=raw：直接把屏幕图片返回给你查看，用于了解当前界面。
                  - captureType=analyze + mode=location：对屏幕图片定位指定 UI 元素，返回归一化坐标（用于精确点击）。
                  - captureType=analyze + mode=caption：对屏幕内容做中文描述（用于确认界面状态、读取屏幕文字）。
                - **bot_chain_tool** — 提交一串桌面操作在本地按序执行。坐标使用归一化值 0-1（相对屏幕宽高），与截屏定位结果一致。
                  - 常用原语：mouse_move（x、y）、mouse_down/mouse_up（button）、mouse_scroll（amount）、key_down/key_up（keys，支持组合键如 ctrl+c）、input_text（text，支持中文）、wait。
                  - 语法糖：click（在当前位置点击）、type（敲击，支持组合键）。
                  - 每个节点可带 delay（毫秒）控制节奏；链级可用 default_delay / head_delay / tail_delay。
                  - 链尾默认截图并随结果返回给你，可直接用于确认操作效果。
                  - 输入文字前请确保输入框已获得焦点（先 mouse_move + click）。

                ## 工作方法（必须严格遵守）
                1. **先看后动**：每一轮开始，先用 screen_capture_tool 观察当前屏幕，确认界面状态与坐标依据。
                2. **一步一验**：提交操作链后，根据链尾截图确认是否生效；失败或结果不符就换策略（换坐标、换路径、增加等待）。
                3. **坐标只信视觉**：所有坐标必须来自截屏与定位结果，归一化到 0-1，严禁凭空猜测。看不清就重新截屏或换用 analyze 定位。
                4. **善用等待**：界面加载、动画、弹窗需要时间，用 wait 或 delay 给足；操作无效时优先怀疑"没等够"。
                5. **不要裸奔**：需要输入的文字、需要点击的目标都要先在截图上确认，再操作。
                6. **收在目标上**：目标达成即停止调用工具并汇报；确认受阻时不要反复尝试同一步，如实说明卡在哪。

                ## 最终答复格式
                停止调用工具后，输出执行结果：
                # 执行结果：{一句话概括任务}
                ## 完成情况
                - 目标是否达成 / 完成到哪一步
                ## 关键步骤
                1. {做了什么，界面如何响应}
                ...
                ## 遇到的问题
                - 若未完成，说明卡在哪、需要用户提供什么（如权限、关闭某弹窗、手动登录）

                ## 严禁行为
                - 禁止编造未在截屏中看到的界面内容与坐标
                - 禁止在未确认的情况下反复重试同一个失败操作
                - 禁止操作与任务目标无关的界面
                - 汇报中的每一步都必须来自真实截屏与工具执行结果""";
    }

    private String buildUserPrompt(String target) {
        return """
                [桌面任务]
                %s

                开始前先截屏看清当前界面，再逐步操作、逐步确认，完成后输出执行结果。""".formatted(target);
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
