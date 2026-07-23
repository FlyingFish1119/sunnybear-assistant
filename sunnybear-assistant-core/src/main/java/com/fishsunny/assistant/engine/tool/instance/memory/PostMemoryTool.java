package com.fishsunny.assistant.engine.tool.instance.memory;

/*
 * @Usage 核心记忆添加/修改工具 —— 支持 add 和 update 两种模式
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.MemoryToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.mvc.service.MemoryService;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 核心记忆添加/修改工具
 * 通过 mode 参数切换：
 * - add：新增一条记忆，id 由系统自动生成
 * - update：修改已有记忆，需提供 id
 */
@ToolKitComponent(MemoryToolKit.class)
@ConditionalOnExpression("${engine.tool.memory.enable:true} && ${engine.tool.memory.post-memory.enable:true}")
public class PostMemoryTool implements ToolHandler {

    public static final String NAME = "post_memory_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;

    public PostMemoryTool(ObjectMapper objectMapper, MemoryService memoryService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        添加或修改一条核心记忆。核心记忆是你与用户长期相处下来积累的重要信息，\
                        会在每次对话时自动注入系统提示词中帮你「记住」用户。\
                        通过 mode 参数切换操作类型：\
                        1) add 模式 - 新增一条记忆；\
                        2) update 模式 - 修改已有记忆，需要提供记忆 id。

                        ## 你应该记住的信息类型（满足任意一条就值得记住）：
                        1. 用户身份与背景：姓名、年龄、职业、所在地、学历、技术栈、公司/团队等；
                        2. 用户偏好与习惯：喜欢的编程语言/框架/工具、常用的工作流、代码风格偏好、饮食口味、作息习惯等；
                        3. 长期上下文与目标：用户正在做的项目目标、长期任务、学习计划等；
                        4. 重要决策与约定：用户做出的技术选型、架构决策、与你达成的行为约定（如「回复用中文」「代码注释用英文」）；
                        5. 关系与社交：用户提到的家人、朋友、同事等重要人物及其关系。

                        ## 不应该记住的信息：
                        1. 临时一次性询问（如「今天天气怎么样」）；
                        2. 已在聊天历史中自然保留的短期对话细节；
                        3. 敏感隐私信息（密码、密钥、身份证号等），遇到这类信息应主动提醒用户不要分享。

                        ## 记忆内容编写要求：
                        1. 每条记忆独立成句，脱离上下文也能被理解；
                        2. 简洁准确，一句话说清一个事实，例如「用户叫张三，是一名 Java 后端开发」「用户偏好使用 IntelliJ IDEA 开发」；
                        3. 如果用户纠正了你之前的认知，应使用 update 模式更新旧记忆而非新增一条，避免信息矛盾；
                        4. 如果一条记忆已经过时或不再适用（如「用户正在学 Python」→ 用户已经学完了），应更新它而非保留旧信息。
                        """.replace("\n", " "))
                .setRequired(List.of("mode", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("mode", "string", "操作模式：add（新增一条记忆）或 update（修改已有记忆）。当用户首次分享某个信息时用 add，当用户纠正或更新已有信息时用 update。"),
                        new ToolRegister.Parameters("content", "string", "记忆内容。要求简洁、独立、准确，一句话表达一个事实。好例子：「用户叫张三，在北京工作，是一名 Java 后端开发」「用户偏好使用 VS Code 而非 IntelliJ IDEA」。坏例子：「他好像说过他喜欢那个东西」（指代不明、模糊）。"),
                        new ToolRegister.Parameters("id", "integer", "（update 模式必填，add 模式忽略）要修改的记忆 ID。修改前请先确认该记忆确实存在且内容已过时。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        if (!StringUtils.hasText(arguments.getMode())) {
            throw new ToolExecutor.ToolExecuteException("参数 mode 不能为空，请指定为 add 或 update");
        }
        if (!StringUtils.hasText(arguments.getContent())) {
            throw new ToolExecutor.ToolExecuteException("参数 content 不能为空");
        }

        String mode = arguments.getMode().trim().toLowerCase();

        try {
            MemoryRecord saved = memoryService.addOrUpdateMemory(arguments.getId(), arguments.getContent(), mode);
            String actionName = "add".equals(mode) ? "新增" : "修改";
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("记忆%s成功:\n  ID: %s\n  内容: %s", actionName, saved.getId(), saved.getContent()));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("记忆操作失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    private static class Arguments {
        private String mode;
        private String content;
        private Integer id;
    }
}
