package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 05:21
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /** 会话名称最大长度 */
    private static final int MAX_SESSION_NAME_LENGTH = 30;

    private final ChatSessionService chatSessionService;

    @Autowired
    public SessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @RequestMapping("/get")
    public RestResponse get(@RequestParam(required = false) String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            ChatSession session = chatSessionService.findById(sessionId);
            return new RestResponse().success(session);
        } catch (Exception e) {
            log.error("获取会话失败: sessionId={}", sessionId, e);
            return new RestResponse().error("获取会话失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get/all")
    public RestResponse getAll(@RequestParam(required = false, defaultValue = "chat") String type) {
        try {
            List<ChatSession> sessions = chatSessionService.findByType(type);
            return new RestResponse().success(sessions);
        } catch (Exception e) {
            log.error("获取会话列表失败: type={}", type, e);
            return new RestResponse().error("获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * keyset 分页获取会话（侧边栏"无限滚动"用）：按 update_time DESC, id DESC。
     * 首屏不带 beforeTime/beforeId；翻页带上一页最旧一条的 updateTime + id 作游标。
     * data: { list: [...], hasMore: boolean }（hasMore 通过多取一条判定）
     */
    @RequestMapping("/get/page")
    public RestResponse getPage(@RequestParam(required = false, defaultValue = "chat") String type,
                                @RequestParam(required = false, defaultValue = "50") int size,
                                @RequestParam(required = false) String beforeTime,
                                @RequestParam(required = false) String beforeId) {
        try {
            if (size <= 0) {
                size = 50;
            }
            if (size > 200) {
                size = 200;
            }
            boolean hasCursor = StringUtils.hasText(beforeTime) && StringUtils.hasText(beforeId);
            List<ChatSession> rows = chatSessionService.findByTypePage(type, size + 1,
                    hasCursor ? beforeTime : null, hasCursor ? beforeId : null);
            boolean hasMore = rows.size() > size;
            List<ChatSession> list = hasMore ? rows.subList(0, size) : rows;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", list);
            data.put("hasMore", hasMore);
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.error("获取会话分页失败: type={}", type, e);
            return new RestResponse().error("获取会话分页失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            chatSessionService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除会话失败: id={}", id, e);
            return new RestResponse().error("删除会话失败: " + e.getMessage());
        }
    }

    /**
     * 切换会话的 Pro 模式（普通 ↔ 高级），直接切换无需确认
     */
    @PostMapping("/toggle-pro")
    public RestResponse togglePro(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            ChatSession session = chatSessionService.findById(id);
            if (session == null) {
                return new RestResponse().error("会话不存在");
            }
            boolean newState = session.getEnablePro() == null || !session.getEnablePro();
            session.setEnablePro(newState);
            chatSessionService.update(session);
            return new RestResponse().success(session);
        } catch (Exception e) {
            log.error("切换 Pro 模式失败: id={}", id, e);
            return new RestResponse().error("切换模式失败: " + e.getMessage());
        }
    }

    /**
     * 切换会话的无审查模式（审查中 ↔ 无审查），开启后该会话内所有工具的确认与 AI 危险审查全部失效。
     * 直接切换无需确认，前端开启时应自行弹出安全提示确认。
     */
    @PostMapping("/toggle-unreviewed")
    public RestResponse toggleUnreviewed(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            ChatSession session = chatSessionService.findById(id);
            if (session == null) {
                return new RestResponse().error("会话不存在");
            }
            boolean newState = session.getUnreviewed() == null || !session.getUnreviewed();
            session.setUnreviewed(newState);
            chatSessionService.update(session);
            return new RestResponse().success(session);
        } catch (Exception e) {
            log.error("切换无审查模式失败: id={}", id, e);
            return new RestResponse().error("切换无审查模式失败: " + e.getMessage());
        }
    }

    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) ChatSession session) {
        if (session == null) {
            return new RestResponse().error("会话不能为空");
        }
        if (!StringUtils.hasText(session.getId())) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        if (! StringUtils.hasText(session.getName())) {
            return new RestResponse().error("会话名称不能为空");
        }
        if (session.getName().length() > MAX_SESSION_NAME_LENGTH) {
            return new RestResponse().error("会话名称不能超过" + MAX_SESSION_NAME_LENGTH + "个字符");
        }
        try {
            ChatSession updatedSession = chatSessionService.update(session);
            return new RestResponse().success(updatedSession);
        } catch (Exception e) {
            log.error("更新会话失败: id={}", session.getId(), e);
            return new RestResponse().error("更新会话失败: " + e.getMessage());
        }
    }
}
