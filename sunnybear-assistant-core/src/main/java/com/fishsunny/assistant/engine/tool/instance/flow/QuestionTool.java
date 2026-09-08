package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 结构化提问工具 —— AI 想向用户澄清几个关键点时使用。
 *        一次调用把若干问题平铺发出（每题可带候选回答、也可自由输入），
 *        前端弹窗展示，用户答完 / 取消后阻塞返回，AI 在同一轮里拿到结果继续。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/8
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ToolQuestion;
import com.fishsunny.assistant.dto.ToolQuestionAnswer;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import com.fishsunny.assistant.mvc.controller.ChatController;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.question.enable:true}")
public class QuestionTool implements ToolHandler {

    public static final String NAME = "question_tool";

    private static final Logger log = LoggerFactory.getLogger(QuestionTool.class);

    /** 单次提问问题数上限：防止 AI 一次性抛出一屏过多问题 */
    private static final int MAX_QUESTIONS = 6;
    /** 每题候选数上限 */
    private static final int MAX_OPTIONS = 6;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public QuestionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        向用户提出结构化问题来澄清信息/推进对话。当需要用户对若干具体点做选择或给简短答复、\
                        且不便让其完全自由发挥时使用：一次调用把全部问题平铺发出，每题可给出几个候选答案（用户可直接点选，也可自由输入）。\
                        调用后当前对话会暂停，直到用户答完（或取消）。 \
                        若用户取消/关闭弹窗，表示用户想以自然对话方式交流，此时 AI 应停止结构化提问、转为自然对话，不要再调用本工具。\
                        收集偏好的开放式访谈请直接自然对话，不要滥用本工具。
                        """)
                .setRequired(List.of("questions"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("message", "string",
                                "全局引导语（可选），展示在问题列表上方，说明为什么问这些。例如「为了给你更合适的建议，先确认几点」。"),
                        new ToolRegister.Parameters("questions", "array",
                                "问题数组，一次不要超过 " + MAX_QUESTIONS + " 个。每个元素为对象：{q (string, 问题文本), options (array<string>, 候选答案，用户可直接点选，可省略；省略则只能自由输入)}。每题候选不超过 " + MAX_OPTIONS + " 个。")
                ));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session"}, type = {ChatSession.class, WebSocketSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        // 定时任务会话：没有可提问的实时用户交互渠道，不能阻塞等待，直接失败并引导 AI
        if (chatSession != null && ChatSession.TYPE_CRON.equals(chatSession.getType())) {
            throw new ToolExecutor.ToolExecuteException(
                    "当前会话（定时任务）没有可提问的用户交互渠道，不能调用 question_tool。请停止调用，改用自然对话输出或基于已有信息继续。");
        }

        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }
        validateArguments(arguments);

        WebSocketSession session = (WebSocketSession) context.get("session");

        ToolQuestion payload = new ToolQuestion().loadInfo(NAME, arguments.getMessage(), null);
        for (int i = 0; i < arguments.getQuestions().size(); i++) {
            ToolQuestion.Question question = new ToolQuestion.Question()
                    .setKey(String.valueOf(i))
                    .setQ(arguments.getQuestions().get(i).getQ());
            if (arguments.getQuestions().get(i).getOptions() != null) {
                question.setOptions(arguments.getQuestions().get(i).getOptions());
            }
            payload.getQuestions().add(question);
        }

        try {
            session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_QUESTION + objectMapper.writeValueAsString(payload)));
            ToolQuestionAnswer answer = ChatController.awaitQuestion(payload.getId(), payload.getTimeout());
            if (answer == null) {
                throw new ToolExecutor.ToolExecuteException("用户未在时间内回答问题，提问已取消。请停止重复调用本工具。");
            }
            if (Boolean.TRUE.equals(answer.getCancelled())) {
                // 用户关闭/退出弹窗 = 想自然聊这个话题 → 返回成功并明确指示 AI
                return new ToolExecutor.ToolExecuteResponse(name(), """
                        用户关闭了提问面板，没有逐题作答，表示想以自然对话的方式聊这个话题。

                        ## 接下来请你这样
                        - 不要再调用 question_tool（或任何问卷/提问工具）强行收集信息；
                        - 转而以自然对话的方式主动引导用户说下去（可就刚才的话题直接发问、抛观点或倾听）。
                        """);
            }
            return new ToolExecutor.ToolExecuteResponse(name(), buildResult(payload, answer));
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("QuestionTool 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("提问失败：" + e.getMessage());
        } finally {
            ChatController.cleanupQuestion(payload.getId());
        }
    }

    // ==================== 组装结果 ====================

    /**
     * 把用户逐题作答转成 AI 可直接引用的 markdown 文本（成功路径）。
     */
    private String buildResult(ToolQuestion payload, ToolQuestionAnswer answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户已作答，结果如下（可直接引用，或就某个回答继续追问）：\n\n");
        int answeredCount = 0;
        for (int i = 0; i < payload.getQuestions().size(); i++) {
            ToolQuestion.Question q = payload.getQuestions().get(i);
            String key = q.getKey();
            String text = answer.getAnswers().stream()
                    .filter(item -> key.equals(item.getKey()))
                    .map(ToolQuestionAnswer.Item::getAnswer)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            String qText = StringUtils.hasText(q.getQ()) ? q.getQ() : "（无题面）";
            if (StringUtils.hasText(text)) {
                answeredCount++;
                sb.append("- **").append(qText).append("** → ").append(text.trim()).append("\n");
            } else {
                sb.append("- **").append(qText).append("** → （用户未作答）\n");
            }
        }
        if (answeredCount == 0) {
            sb.append("\n（用户似乎没有留下有效回答，建议用自然对话的方式追问澄清。）");
        }
        return sb.toString();
    }

    // ==================== 校验 ====================

    private void validateArguments(Arguments arguments) throws ToolExecutor.ToolExecuteException {
        if (arguments == null || CollectionUtils.isEmpty(arguments.getQuestions())) {
            throw new ToolExecutor.ToolExecuteException("参数 questions 不能为空，至少提供一个问题");
        }
        if (arguments.getQuestions().size() > MAX_QUESTIONS) {
            throw new ToolExecutor.ToolExecuteException("单次提问不能超过 " + MAX_QUESTIONS + " 个问题，请拆分或精简");
        }
        for (int i = 0; i < arguments.getQuestions().size(); i++) {
            QuestionArg question = arguments.getQuestions().get(i);
            if (question == null || !StringUtils.hasText(question.getQ())) {
                throw new ToolExecutor.ToolExecuteException("第 " + (i + 1) + " 个问题的 q（题面）不能为空");
            }
            if (question.getOptions() != null && question.getOptions().size() > MAX_OPTIONS) {
                throw new ToolExecutor.ToolExecuteException("第 " + (i + 1) + " 个问题的候选回答不能超过 " + MAX_OPTIONS + " 个");
            }
        }
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
        private String message;
        private List<QuestionArg> questions;
    }

    @Data
    @Accessors(chain = true)
    private static class QuestionArg {
        private String q;
        private List<String> options;
    }
}
