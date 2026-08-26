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

import java.util.List;

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
