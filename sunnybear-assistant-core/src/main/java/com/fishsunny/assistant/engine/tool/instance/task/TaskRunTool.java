package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务执行工具 - 异步执行任务的所有步骤
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 08:14
 */

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.*;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.mvc.service.TaskPromptService;
import com.fishsunny.assistant.utils.ToolContextBuilder;
import com.fishsunny.assistant.mvc.service.TaskService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.variable.ControlSign;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-run.enable:true}")
public class TaskRunTool implements ToolHandler {

    public static final String NAME = "task_run_tool";

    private static final Logger log = LoggerFactory.getLogger(TaskRunTool.class);

    private final AtomicBoolean cas = new AtomicBoolean(false);

    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final AISettings taskAISettings;
    private final AISettings cubAISettings;
    private final TaskPromptService taskPromptService;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ToolCallLoop toolCallLoop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Autowired
    public TaskRunTool(TaskService taskService, ObjectMapper objectMapper,
                       @Qualifier(AISettings.TASK) AISettings taskAISettings,
                       @Qualifier(AISettings.CUB) AISettings cubAISettings,
                       TaskPromptService taskPromptService,
                       ChatHttpHandler chatHttpHandler,
                       @Lazy ToolExecutor toolExecutor,
                       ToolCallLoop toolCallLoop) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.cubAISettings = cubAISettings;
        this.taskAISettings = taskAISettings;
        this.taskPromptService = taskPromptService;
        this.chatHttpHandler = chatHttpHandler;
        this.toolExecutor = toolExecutor;
        this.toolCallLoop = toolCallLoop;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        if (!(context.get("session") instanceof WebSocketSession)) {
            throw new ToolExecutor.ToolExecuteException("工具内部错误导致此工具不可使用，原因: session 依赖缺失");
        }
        try {
            if (!cas.compareAndSet(false, true)) {
                throw new ToolExecutor.ToolExecuteException("工具正在执行中，请稍后再试");
            }

            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getTaskId())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务 ID");
            }

            // 校验任务存在
            TaskService.TheTask theTask = taskService.selectTaskById(arguments.getTaskId());
            if (theTask == null) {
                throw new ToolExecutor.ToolExecuteException("任务不存在: " + arguments.getTaskId());
            }

            Task task = theTask.task();
            List<TaskStep> steps = theTask.taskSteps();

            // failed 允许重跑 —— 执行时会跳过已完成步骤、从失败处断点续跑，结果从数据库读取注入。
            if (Task.STATUS_RUNNING.equals(task.getStatus())) {
                throw new ToolExecutor.ToolExecuteException("任务正在执行中，不能重复启动: " + task.getTaskName());
            }
            if (Task.STATUS_FINISHED.equals(task.getStatus())) {
                throw new ToolExecutor.ToolExecuteException("任务已完成，不能再次执行。如需重新执行，请创建新任务。");
            }

            // 确认机制：始终要求用户确认（无审查模式跳过）
            String uuid = UUID.randomUUID().toString();
            try {
                if (!ToolContextBuilder.isUnreviewed(context)) {
                    ask(uuid, (WebSocketSession) context.get("session"), task, steps);
                }
            } finally {
                ChatController.cleanupConfirm(uuid);
            }

            // 异步执行
            executor.submit(() -> {
                try {
                    execute(context, task);
                } catch (Exception e) {
                    log.error("任务执行异常: taskId={}, error={}", task.getId(), e.getMessage(), e);
                }
            });

            String sb = "任务已开始异步执行\n\n" +
                    "- 任务 ID: " + task.getId() + "\n" +
                    "- 任务名称: **" + task.getTaskName() + "**\n" +
                    "- 步骤数: **" + steps.size() + "**\n" +
                    "\n任务将在后台异步执行，可通过 task_read_tool 查询任务状态了解进度。";

            return new ToolExecutor.ToolExecuteResponse(name(), sb);
        } catch (ToolExecutor.ToolExecuteException e) {
            cas.set(false);
            throw e;
        } catch (Exception e) {
            cas.set(false);
            throw new ToolExecutor.ToolExecuteException("启动任务失败：" + e.getMessage());
        }
    }

    /**
     * AI 无法完成任务时输出的失败标记，格式：$[TASK_FAILURE: 失败原因]$
     */
    private static final Pattern TASK_FAILURE_PATTERN = Pattern.compile("\\$\\[TASK_FAILURE:\\s*(.+?)]\\$", Pattern.DOTALL);

    private static final List<Class<? extends ToolKit>> includeKits = List.of(
            FileToolKit.class, NetToolKit.class, ImageToolKit.class, OSToolKit.class);

    /**
     * 异步执行任务的所有步骤
     */
    private void execute(Map<String, Object> context, Task task) throws ToolExecutor.ToolExecuteException {
        TaskService.TheTask current = taskService.selectTaskById(task.getId());
        // 以执行时的最新快照为准（failed 重跑时步骤可能已被部分执行/部分完成）
        List<TaskStep> steps = current.taskSteps();
        try {
            taskService.updateTaskStatus(task.getId(), Task.STATUS_RUNNING);
        } catch (Exception e) {
            log.error("任务更新异常: taskId={}, error={}", task.getId(), e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("任务更新异常: " + e.getMessage());
        } finally {
            cas.set(false);
        }
        try {
            StringBuilder flow = new StringBuilder();
            for (TaskStep step : steps) {
                // failed 任务断点续跑：跳过已完成步骤，从数据库读取其结果注入 flow，不重新执行
                if (Task.STATUS_FINISHED.equals(step.getStatus()) && StringUtils.hasText(step.getResult())) {
                    flow.append("[步骤").append(step.getSort()).append("] ")
                            .append(step.getStepName()).append("，以完成。\n[完成结果]：")
                            .append(step.getResult()).append("\n\n");
                    continue;
                }

                taskService.updateTaskStepStatus(step.getId(), Task.STATUS_RUNNING);

                String prompt = createStepPrompt(task, step);
                List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegister(toolExecutor, includeKits);

                String userPrompt = """
                        [总目标]：${target}
                        [你被分配到的步骤]：${step}
                        开始执行你的任务，并严格按照要求完成。

                        ## 失败处理
                        如果你认为当前步骤无法完成（例如：缺少必要信息、依赖的前置条件不满足、工具返回错误且无法恢复），你必须停止尝试，输出以下格式的失败声明后立即停止：
                        $[TASK_FAILURE: 具体失败原因]$
                        输出此声明后，不要再进行任何工具调用或后续操作。
                        """.replace("${target}", task.getTaskDesc()).replace("${step}", step.getStepDesc());
                if (StringUtils.hasText(flow)) {
                    userPrompt = """
                        [总目标]：
                        ${target}
                        [你被分配到的步骤]：
                        ${step}
                        [你的之前步骤的输出结果]：
                        ${flow}

                        开始执行你的任务，并严格按照要求完成。

                        ## 失败处理
                        如果你认为当前步骤无法完成（例如：缺少必要信息、依赖的前置条件不满足、工具返回错误且无法恢复），你必须停止尝试，输出以下格式的失败声明后立即停止：
                        $[TASK_FAILURE: 具体失败原因]$
                        输出此声明后，不要再进行任何工具调用或后续操作。
                        """.replace("${target}", task.getTaskDesc())
                        .replace("${step}", step.getStepDesc())
                        .replace("${flow}", flow);
                }


                List<ChatMessage> messages = new ArrayList<>();
                messages.add(new ChatMessage().system(prompt));
                messages.add(new ChatMessage().user(userPrompt));
                ChatRequest request = new ChatRequest()
                        .loadSettings(taskAISettings)
                        .setMessages(messages)
                        .setTools(toolRegisters);
                AtomicReference<String> result = new AtomicReference<>();

                result.set(toolCallLoop.execute(taskAISettings, request, context, new ToolCallLoop.AgentLoopHook(toolCallLoop.createDefaultLogback(context), null)));

                // 检查 AI 是否输出了失败标记
                Matcher failureMatcher = TASK_FAILURE_PATTERN.matcher(result.get());
                if (failureMatcher.find()) {
                    String failureReason = failureMatcher.group(1).trim();
                    log.warn("任务步骤执行失败: taskId={}, stepId={}, stepName={}, reason={}",
                            task.getId(), step.getId(), step.getStepName(), failureReason);
                    taskService.updateTaskStepStatus(step.getId(), Task.STATUS_FAILED);
                    taskService.updateTaskStatus(task.getId(), Task.STATUS_FAILED);
                    return;
                }

                String builder = "[步骤" + step.getSort() + "] " +
                        step.getStepName() + "，以完成。" + "\n" +
                        "[完成结果]：" + result;
                taskService.finishStep(step.getId(), result.get());
                flow.append(builder).append("\n\n");
            }
            taskService.finishTask(task.getId());
        } catch (Exception e) {
            log.error("任务执行异常: taskId={}, error={}", task.getId(), e.getMessage(), e);
            try {
                taskService.updateTaskStatus(task.getId(), Task.STATUS_FAILED);
            } catch (Exception statusEx) {
                log.error("更新任务失败状态异常: taskId={}, error={}", task.getId(), statusEx.getMessage());
            }
            throw new RuntimeException("任务[" + task.getTaskName() + "]执行异常：" + e.getMessage());
        }
    }

    /**
     * AI 从 task_prompt 表中选择最合适的系统提示词，不再由 AI 直接生成。
     * AI 分析步骤内容后输出 JSON：{"type": "xxx"}，查表即得 system prompt。
     * 步骤具体信息由 user prompt 提供，system prompt 只管角色和行为约束。
     */
    private String createStepPrompt(Task task, TaskStep step) throws Exception {
        // 1. 列出所有可用类型
        List<TaskPrompt> allPrompts = taskPromptService.listAll();
        StringBuilder typeList = new StringBuilder();
        for (TaskPrompt p : allPrompts) {
            typeList.append("- **").append(p.getType()).append("**: ").append(p.getDescription()).append("\n");
        }

        // 2. 构建分类请求，让 AI 选择最合适的 type
        String classificationPrompt = """
                你是一个任务分类器。根据步骤的内容，从下方可用的提示词类型中选择最合适的一个。
                只输出 JSON，不要包含任何其他文字。

                可用类型：
                """ + typeList + """

                任务总目标：${taskName}
                步骤名称：${stepName}
                步骤描述：${stepDesc}

                请输出你的选择（严格 JSON 格式）：
                {"type": "<选中的类型>"}

                如果没有可供选择的提示词，则输出 {"type": null}
                """
                .replace("${taskName}", task.getTaskName())
                .replace("${stepName}", step.getStepName())
                .replace("${stepDesc}", step.getStepDesc());

        ChatRequest classificationRequest = new ChatRequest()
                .loadSettings(new AISettings().copy(cubAISettings).json())
                .setMessages(List.of(new ChatMessage().user(classificationPrompt)));

        // 3. 调用 AI 进行分类
        AtomicReference<String> rawJson = new java.util.concurrent.atomic.AtomicReference<>();
        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                cubAISettings.getAdapterName(),
                classificationRequest,
                cubAISettings.getStream(),
                null,
                (result, lastRes) -> rawJson.set(result.content())
        );

        // 4. 解析 AI 返回的 JSON，提取 type
        String selectedType = parseTypeFromJson(rawJson.get());
        log.info("TaskPrompt AI 选择: type={}, step={}", selectedType, step.getStepName());

        // 5. 查表获取 prompt，直接作为 system prompt（step 信息已在 user prompt 中）
        TaskPrompt config = allPrompts.stream()
                .filter(p -> p.getType().equals(selectedType))
                .findFirst()
                .orElse(TaskPrompt.DEFAULT_PROMPT);
        String finalPrompt = config.getPrompt();

        // 6. JSON 输出最终选中信息
        log.info("TaskPrompt 最终使用: {{\"type\": \"{}\", \"fallback\": {}}}",
                config.getType(), !selectedType.equals(config.getType()));

        return finalPrompt;
    }

    /**
     * 从 AI 返回的文本中解析 type 字段。
     * 兼容纯 JSON、带 markdown 代码块包裹的情况。
     */
    private String parseTypeFromJson(String raw) {
        try {
            String json = raw.trim();
            JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
            Map<String, String> result = objectMapper.readValue(json, mapType);
            return result.get("type").trim();
        } catch (Exception e) {
            log.warn("解析 AI 分类结果失败，回退到 default。raw={}, error={}", raw, e.getMessage());
        }
        return "default";
    }

    /**
     * 向用户发送确认请求并等待响应。
     */
    private void ask(String uuid, WebSocketSession session, Task task, List<TaskStep> steps) throws Exception {
        StringBuilder stepList = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            TaskStep step = steps.get(i);
            stepList.append(i + 1).append(". **").append(step.getStepName()).append("**");
            if (StringUtils.hasText(step.getStepDesc())) {
                stepList.append(" — ").append(step.getStepDesc());
            }
            if (Task.STATUS_FINISHED.equals(step.getStatus())) {
                stepList.append(" `✅ 已完成（将跳过）`");
            }
            stepList.append("\n");
        }

        String message = (Task.STATUS_FAILED.equals(task.getStatus())
                ? "> ⚠️ **该任务此前执行失败，本次将断点续跑**：已完成的步骤会跳过，其结果已从数据库读取并注入后续步骤。\n\n"
                : "")
                + "### 任务执行请求\n\n"
                + "AI 请求执行以下任务：\n\n"
                + "| 属性 | 内容 |\n"
                + "|------|------|\n"
                + "| 任务 ID | `" + task.getId() + "` |\n"
                + "| 任务名称 | **" + task.getTaskName() + "** |\n"
                + "| 任务状态 | `" + task.getStatus() + "` |\n"
                + "| 步骤数 | **" + steps.size() + "** |\n"
                + "| 任务描述 | " + (StringUtils.hasText(task.getTaskDesc()) ? task.getTaskDesc() : "(无)") + " |\n"
                + "\n**步骤列表：**\n" + stepList + "\n"
                + "> ⚠️ 任务将在后台异步执行，可能涉及多次 AI 调用和工具操作。请确认后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message)
                .setTimeout(30);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        Boolean result = ChatController.awaitConfirm(uuid, 30);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认任务执行，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整任务。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了任务执行，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整任务。");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return new ToolRegister()
                .setName(NAME)
                .setDescription("异步执行任务的所有步骤（每次需确认）。仅 waiting 或 failed 状态的任务可执行：failed 任务重跑时跳过已完成步骤、从失败处断点续跑。running 或已完成的会被拒绝。调用后立即返回，通过 task_read_tool 查询进度。")
                .setRequired(List.of("taskId"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("taskId", "string", "要执行的任务 ID。执行前建议先用 task_read_tool 确认任务内容正确，且状态为 waiting（等待执行）或 failed（失败后可断点续跑）")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String taskId;
    }
}
