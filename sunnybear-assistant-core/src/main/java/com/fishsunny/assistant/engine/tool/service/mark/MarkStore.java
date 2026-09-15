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
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MarkStore {

    /** ChatSession.extension 中本模块占用的 namespace 键 */
    public static final String EXTENSION_KEY = "chat_mark";

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

    // ==================== 内部 ====================

    private Map<String, Object> parseMap(String extension) {
        if (!StringUtils.hasText(extension)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(extension, new TypeReference<>() {
            });
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            log.warn("解析会话 extension 失败，按空对象处理: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<Mark> parse(String extension) {
        Object raw = parseMap(extension).get(EXTENSION_KEY);
        if (raw == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.convertValue(raw, new TypeReference<List<Mark>>() {
            });
        } catch (Exception e) {
            log.warn("解析步骤清单失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
