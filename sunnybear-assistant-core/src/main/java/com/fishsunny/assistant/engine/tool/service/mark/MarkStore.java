package com.fishsunny.assistant.engine.tool.service.mark;

/*
 * @Usage 步骤清单存储 —— mark 列表以 namespace 键 "chat_mark" 存进 ChatSession.extension。
 *        写入统一走 ChatSessionService.mergeExtension（append-only，只覆盖自己的键，
 *        不打扰角色/世界等其它消费方，也不动 update_time）；保存后通过 WebSocket
 *        把完整数组推给前端实时跟踪。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/14
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.Mark;
import com.fishsunny.assistant.dto.MarkPayload;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolResponseHandleChain;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MarkStore implements ToolResponseHandleChain {

    /** ChatSession.extension 中本模块占用的 namespace 键 */
    public static final String EXTENSION_KEY = "chat_mark";

    /** 维护步骤清单的工具名：其响应本身已含全量清单，后置处理链跳过它 */
    public static final String TOOL_NAME = "mark_upsert_tool";

    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    public MarkStore(ChatSessionService chatSessionService, ObjectMapper objectMapper) {
        this.chatSessionService = chatSessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 合并保存步骤清单：经 mergeExtension 只覆盖 chat_mark 键，保留其它字段。
     * 返回本次写入的清单。
     */
    public List<Mark> save(ChatSession session, List<Mark> marks) {
        if (session == null || !StringUtils.hasText(session.getId())) {
            throw new IllegalArgumentException("缺少会话信息，无法保存步骤清单");
        }
        List<Mark> safeMarks = marks == null ? new ArrayList<>() : marks;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(EXTENSION_KEY, safeMarks);
        Map<String, Object> merged = chatSessionService.mergeExtension(session.getId(), fields);
        // 同步内存对象，保证同轮后续调用与 UPDATE_SESSION 都带上最新清单
        session.setExtension(merged);
        return safeMarks;
    }

    /** 通过 WebSocket 把完整步骤数组推送给前端（###MARK### + MarkPayload） */
    public void push(WebSocketSession session, String sessionId, List<Mark> marks) {
        if (session == null || !StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            MarkPayload payload = new MarkPayload()
                    .setSessionId(sessionId)
                    .setMarks(marks == null ? new ArrayList<>() : marks);
            session.sendMessage(new TextMessage(
                    ControlSign.SIGN_MARK + objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("推送步骤清单失败 [{}]: {}", sessionId, e.getMessage());
        }
    }

    // ==================== 后置处理链 ====================

    /**
     * 工具响应后置处理：把会话中「进行中 + 待处理」的步骤追加到本批次最后一条工具结果里，
     * 提醒 AI 继续推进清单；本批次调用了 mark 工具时不追加（其响应已含全量清单），
     * 清单清空或全部完成/取消时也不追加。只追加一条避免同轮重复。
     */
    @Override
    public void handle(List<ToolExecutor.ToolExecuteResponse> batchResponses, Map<String, Object> context) {
        if (batchResponses == null || batchResponses.isEmpty() || context == null) {
            return;
        }
        for (ToolExecutor.ToolExecuteResponse response : batchResponses) {
            if (response != null && TOOL_NAME.equals(response.getName())) {
                return;
            }
        }
        ChatSession session = context.get("chatSession") instanceof ChatSession cs ? cs : null;
        List<Mark> unfinished = load(session).stream()
                .filter(mark -> !Mark.STATUS_COMPLETED.equals(mark.getStatus())
                        && !Mark.STATUS_CANCELLED.equals(mark.getStatus()))
                .sorted(Comparator.comparingInt((Mark mark) -> Mark.STATUS_IN_PROGRESS.equals(mark.getStatus()) ? 0 : 1))
                .toList();
        if (unfinished.isEmpty()) {
            return;
        }
        ToolExecutor.ToolExecuteResponse last = batchResponses.getLast();
        if (last == null) {
            return;
        }
        // 提醒作为正文之后的独立文本块追加，不污染工具结果本体（result 是喂回模型的工具返回值，
        // 混进系统提醒会让人分不清哪是工具输出、哪是系统提示；独立块的展示语义与后台任务通知一致）
        last.appendTextContent(buildReminder(unfinished).stripTrailing());
    }

    @Override
    public int getOrder() {
        return 0;
    }

    // ==================== 读取 ====================

    /** 从会话内存 extension 读取步骤清单，读不到/解析失败一律按空清单处理 */
    public List<Mark> load(ChatSession session) {
        if (session == null || session.getExtension() == null) {
            return new ArrayList<>();
        }
        Object raw = session.getExtension().get(EXTENSION_KEY);
        if (raw == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.convertValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析步骤清单失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== 内部 ====================

    /** 未完成清单提醒文本：进行中在前，待处理在后 */
    private String buildReminder(List<Mark> unfinished) {
        StringBuilder sb = new StringBuilder("当前步骤清单：\n");
        for (Mark mark : unfinished) {
            boolean inProgress = Mark.STATUS_IN_PROGRESS.equals(mark.getStatus());
            sb.append("- ").append(inProgress ? "[~]" : "[ ]").append(" ").append(mark.getContent());
            if (inProgress) {
                sb.append("  ← 当前聚焦");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
