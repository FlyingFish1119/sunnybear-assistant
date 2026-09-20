package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 步骤清单更新工具 —— AI 维护一份面向用户展示的多步任务清单。
 *        每次调用传入当前会话的「完整」步骤数组，后端落盘到会话文件并整包推送给前端，
 *        前端持续跟踪渲染；把某条置为 in_progress 表示"当前聚焦"，置为 completed 表示完成。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/14
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.Mark;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.service.mark.MarkStore;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.mark-upsert.enable:true}")
public class MarkUpsertTool implements ToolHandler {

    public static final String NAME = MarkStore.TOOL_NAME;

    private static final Logger log = LoggerFactory.getLogger(MarkUpsertTool.class);

    /** 单次清单步骤数上限：防止 AI 把清单铺得过长 */
    private static final int MAX_MARKS = 30;

    private final MarkStore markStore;
    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public MarkUpsertTool(MarkStore markStore, ObjectMapper objectMapper) {
        this.markStore = markStore;
        this.objectMapper = objectMapper;
        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        维护一份面向用户实时展示的步骤清单。当任务需要多个步骤、且希望用户能持续看到\
                        当前进行到哪一步时使用：每次调用传入「完整」的步骤数组（包含已完成的），\
                        后端会整包推送给前端，用户端会实时显示进度。\
                        步骤状态：pending（待处理）、in_progress（正在处理，即当前聚焦）、completed（已完成）、cancelled（已取消）。\
                        同一时刻最多只能有一条 in_progress，代表你当前正在做的步骤；开始新步骤前，先把上一条置为 completed。\
                        步骤 id 请保持稳定（如 "1"、"2"…），多次调用沿用同一 id，前端才能连续跟踪。\
                        传入空数组表示清空清单。简单的单步任务无需调用本工具。
                        """.replace("\n", " "))
                .setRequired(List.of("marks"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("marks", "array",
                                "完整步骤数组（每次都要传全量，不能只传变化的那条）。最多 " + MAX_MARKS + " 条。")
                                .setItems(ToolRegister.Parameters.object(
                                        "单个步骤",
                                        List.of(
                                                new ToolRegister.Parameters("id", "string",
                                                        "步骤稳定标识，跨多次调用保持一致，例如 \"1\"、\"2\""),
                                                new ToolRegister.Parameters("content", "string",
                                                        "步骤内容，简洁描述要做什么"),
                                                new ToolRegister.Parameters("status", "string",
                                                        "状态：pending | in_progress | completed | cancelled，最多一条 in_progress")),
                                        List.of("id", "content", "status"))
                                )
                ));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession"}, type = {ChatSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();
        if (!StringUtils.hasText(sessionId)) {
            throw new ToolExecutor.ToolExecuteException("缺少会话信息，无法保存步骤清单");
        }

        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }
        List<Mark> marks = validateAndNormalize(arguments);

        try {
            markStore.save(chatSession, marks);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("保存步骤清单失败: " + e.getMessage());
        }

        WebSocketSession session = context.get("session") instanceof WebSocketSession ws ? ws : null;
        markStore.push(session, sessionId, marks);

        return new ToolExecutor.ToolExecuteResponse(name(), buildResult(marks));
    }

    // ==================== 校验 ====================

    private List<Mark> validateAndNormalize(Arguments arguments) throws ToolExecutor.ToolExecuteException {
        if (arguments == null || arguments.getMarks() == null) {
            throw new ToolExecutor.ToolExecuteException("参数 marks 不能为空，若要清空清单请传空数组 []");
        }
        List<Mark> marks = arguments.getMarks();
        if (marks.size() > MAX_MARKS) {
            throw new ToolExecutor.ToolExecuteException("步骤数不能超过 " + MAX_MARKS + " 条，请精简");
        }

        Set<String> ids = new HashSet<>();
        int inProgressCount = 0;
        for (int i = 0; i < marks.size(); i++) {
            Mark mark = marks.get(i);
            int no = i + 1;
            if (mark == null) {
                throw new ToolExecutor.ToolExecuteException("第 " + no + " 条步骤不能为空");
            }
            if (!StringUtils.hasText(mark.getId())) {
                throw new ToolExecutor.ToolExecuteException("第 " + no + " 条步骤的 id 不能为空");
            }
            if (!StringUtils.hasText(mark.getContent())) {
                throw new ToolExecutor.ToolExecuteException("步骤[" + mark.getId() + "]的 content 不能为空");
            }
            if (!Mark.isValidStatus(mark.getStatus())) {
                throw new ToolExecutor.ToolExecuteException("步骤[" + mark.getId()
                        + "]的状态非法: " + mark.getStatus() + "，只能是 pending | in_progress | completed | cancelled");
            }
            if (!ids.add(mark.getId())) {
                throw new ToolExecutor.ToolExecuteException("步骤 id 重复: " + mark.getId());
            }
            if (Mark.STATUS_IN_PROGRESS.equals(mark.getStatus())) {
                inProgressCount++;
            }
        }
        if (inProgressCount > 1) {
            throw new ToolExecutor.ToolExecuteException("最多只能有一条 in_progress 步骤，当前有 " + inProgressCount + " 条");
        }
        return marks;
    }

    // ==================== 结果文本 ====================

    private String buildResult(List<Mark> marks) {
        if (CollectionUtils.isEmpty(marks)) {
            return "步骤清单已清空。";
        }
        StringBuilder sb = new StringBuilder();
        long done = marks.stream().filter(m -> Mark.STATUS_COMPLETED.equals(m.getStatus())).count();
        sb.append("步骤清单已更新（").append(done).append("/").append(marks.size()).append(" 已完成）：\n\n");
        for (Mark mark : marks) {
            sb.append("- ").append(symbol(mark.getStatus())).append(" ").append(mark.getContent());
            if (Mark.STATUS_IN_PROGRESS.equals(mark.getStatus())) {
                sb.append("  ← 当前聚焦");
            }
            sb.append("\n");
        }
        sb.append("\n每完成一步请再次调用本工具更新状态，并保持 id 不变。");
        return sb.toString();
    }

    private String symbol(String status) {
        return switch (status) {
            case Mark.STATUS_COMPLETED -> "[x]";
            case Mark.STATUS_IN_PROGRESS -> "[~]";
            case Mark.STATUS_CANCELLED -> "[-]";
            default -> "[ ]";
        };
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
        private List<Mark> marks;
    }
}
