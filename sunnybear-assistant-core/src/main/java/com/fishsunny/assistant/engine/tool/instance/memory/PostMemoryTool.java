package com.fishsunny.assistant.engine.tool.instance.memory;

/*
 * @Usage 核心记忆添加/修改工具 —— 支持 add 和 update 两种模式
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.MemoryToolKit;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

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
    private final SecurityService securityService;

    public PostMemoryTool(ObjectMapper objectMapper, MemoryService memoryService, SecurityService securityService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.securityService = securityService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("添加或更新一条核心记忆。mode=add 新增，mode=update 修改已有记忆（需提供 id）。记忆会在后续对话中自动注入上下文。（每次写入需用户确认）")
                .setRequired(List.of("mode", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("mode", "string", "操作模式：add（新增）或 update（修改已有记忆）"),
                        new ToolRegister.Parameters("content", "string", "记忆内容。一句话一个事实，简洁独立。如'用户叫张三，是一名 Java 后端开发'"),
                        new ToolRegister.Parameters("id", "integer", "（update 模式必填）要修改的记忆 ID")
                ));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session"}, type = {ChatSession.class, WebSocketSession.class})
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
            // update 模式先读原内容用于确认框对比；记忆不存在则直接拒绝，避免先弹确认框再执行失败
            MemoryRecord original = null;
            if ("update".equals(mode)) {
                original = memoryService.getMemoryById(arguments.getId());
                if (original == null) {
                    throw new ToolExecutor.ToolExecuteException("记忆不存在，无法修改: id=" + arguments.getId());
                }
            }

            // 确认机制：写入前必须用户确认（无审查/定时任务由 SecurityService 统一裁决）
            ask(context, mode, original, arguments);

            MemoryRecord saved = memoryService.addOrUpdateMemory(arguments.getId(), arguments.getContent(), mode);
            String actionName = "add".equals(mode) ? "新增" : "修改";
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("记忆%s成功:\n  ID: %s\n  内容: %s", actionName, saved.getId(), saved.getContent()));
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("记忆操作失败: " + e.getMessage());
        }
    }

    /**
     * 向用户发送记忆写入确认请求并等待响应。
     */
    private void ask(Map<String, Object> context, String mode, MemoryRecord original, Arguments arguments) throws Exception {
        StringBuilder message = new StringBuilder("### 核心记忆写入确认\n\n");
        if ("add".equals(mode)) {
            message.append("AI 请求新增一条核心记忆：\n\n")
                    .append("| 操作 | 内容 |\n")
                    .append("|------|------|\n")
                    .append("| 记忆内容 | ").append(arguments.getContent()).append(" |\n");
        } else {
            message.append("AI 请求修改一条核心记忆：\n\n")
                    .append("| 操作 | 内容 |\n")
                    .append("|------|------|\n")
                    .append("| 记忆 ID | ").append(arguments.getId()).append(" |\n")
                    .append("| 原内容 | ").append(original == null ? "(未知)" : original.getContent()).append(" |\n")
                    .append("| 新内容 | ").append(arguments.getContent()).append(" |\n");
        }
        message.append("\n> ⚠️ 确认后该记忆会长期注入后续对话上下文。若内容不重要或只是一次性信息，请拒绝。");
        securityService.ask(name(), message.toString(), 60, context);
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
