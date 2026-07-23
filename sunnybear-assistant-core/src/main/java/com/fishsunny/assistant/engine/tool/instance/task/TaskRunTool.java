package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务执行工具 - 异步执行任务的所有步骤
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 08:14
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
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
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ToolCallLoop toolCallLoop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Autowired
    public TaskRunTool(TaskService taskService, ObjectMapper objectMapper,
                       @Qualifier(AISettings.TASK) AISettings taskAISettings,
                       @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                       ChatHttpHandler chatHttpHandler,
                       @Lazy ToolExecutor toolExecutor,
                       ToolCallLoop toolCallLoop) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.taskAISettings = taskAISettings;
        this.missionAISettings = missionAISettings;
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

                String prompt = createStepPrompt(task, step, steps);
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
     * 创建步骤的提示词，用于优化每一个 AI 模型执行
     */
    private String createStepPrompt(Task task, TaskStep step, List<TaskStep> steps) throws Exception {
        String prompt = """
                你是一位资深提示词工程师，专门为多步骤自动化流水线中的“单一步骤 AI”设计系统提示词。
                你会收到用户提供的两段信息：
                - 总体目标：完整流水线的最终产出
                - 当前步骤：当前这个 AI 需要完成的唯一子任务
                
                你的任务是基于以上信息，生成一份专门给“当前步骤 AI”使用的系统提示词。
                该系统提示词必须严格满足以下要求：
                
                1. 职责边界锁定
                   - 明确告知 AI，它的唯一职责是完成「当前步骤」所描述的具体工作。
                   - 必须用强烈语气禁止 AI 尝试完成总体目标、提前执行后续步骤、或者给出整体解决方案。
                   - 约束 AI 只输出与本步骤直接相关的内容，不扩展、不越界。
                
                2. 步骤完成后的强制总结
                   - 要求 AI 在完成该步骤的工作后，额外输出一段独立的“步骤总结”。
                   - 总结中需包含：本步骤做了什么、产出了什么关键结果。
                
                请直接输出生成的系统提示词，不要包含任何你的附加解释或开场白。输出内容即为可直接复制使用的“步骤 AI 系统提示词”。
                """;
        String userPrompt = "[总目标]：" + task.getTaskName() + "\n" +
                "[当前步骤]：" + step.getStepName() + "\n" +
                "[步骤描述]：" + step.getStepDesc() + "\n";

        ChatRequest request = new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(prompt),
                        new ChatMessage().user(userPrompt))
                );
        java.util.concurrent.atomic.AtomicReference<String> generatedText = new java.util.concurrent.atomic.AtomicReference<>();
        chatHttpHandler.translate(UUID.randomUUID().toString(), missionAISettings.getAdapterName(), request, missionAISettings.getStream(),
                null,
                ((result, lastRes) -> generatedText.set(result.content()))
        );
        return generatedText.get();
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
