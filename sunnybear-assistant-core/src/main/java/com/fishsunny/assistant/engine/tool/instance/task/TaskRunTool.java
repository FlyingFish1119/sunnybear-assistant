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
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.*;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.mvc.service.TaskPromptService;
import com.fishsunny.assistant.mvc.service.TaskService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-run.enable:true}")
public class TaskRunTool implements ToolHandler {

    public static final String NAME = "task_run_tool";

    private static final Logger log = LoggerFactory.getLogger(TaskRunTool.class);

    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final AISettings taskAISettings;
    private final AISettings missionAISettings;
    private final TaskPromptService taskPromptService;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ToolCallLoop toolCallLoop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Autowired
    public TaskRunTool(TaskService taskService, ObjectMapper objectMapper,
                       @Qualifier(AISettings.TASK) AISettings taskAISettings,
                       @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                       TaskPromptService taskPromptService,
                       ChatHttpHandler chatHttpHandler,
                       @Lazy ToolExecutor toolExecutor,
                       ToolCallLoop toolCallLoop) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
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

            // 异步执行
            executor.submit(() -> {
                try {
                    execute(context, task, steps);
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
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("启动任务失败：" + e.getMessage());
        }
    }

    /**
     * AI 无法完成任务时输出的失败标记，格式：$[TASK_FAILURE: 失败原因]$
     */
    private static final Pattern TASK_FAILURE_PATTERN = Pattern.compile("\\$\\[TASK_FAILURE:\\s*(.+?)\\]\\$", Pattern.DOTALL);

    private static final List<Class<? extends ToolKit>> includeKits = List.of(
            FileToolKit.class, NetToolKit.class, ImageToolKit.class, OSToolKit.class);

    /**
     * 异步执行任务的所有步骤
     */
    private void execute(Map<String, Object> context, Task task, List<TaskStep> steps) {
        try {
            taskService.updateTaskStatus(task.getId(), Task.STATUS_RUNNING);
        } catch (Exception e) {
            log.error("任务更新异常: taskId={}, error={}", task.getId(), e.getMessage(), e);
        }
        try {
            StringBuilder flow = new StringBuilder();
            for (TaskStep step : steps) {
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
                .loadSettings(missionAISettings)
                .setMessages(List.of(new ChatMessage().user(classificationPrompt)));

        // 3. 调用 AI 进行分类
        AtomicReference<String> rawJson = new java.util.concurrent.atomic.AtomicReference<>();
        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                missionAISettings.getAdapterName(),
                classificationRequest,
                missionAISettings.getStream(),
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

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        异步执行任务的所有步骤。调用后立即返回，通过 task_read_tool 查询进度。
                        """.replace("\n", " "))
                .setRequired(List.of("taskId"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("taskId", "string", "要执行的任务 ID。执行前建议先用 task_read_tool 确认任务内容正确且状态为 waiting（等待执行）")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String taskId;
    }
}
