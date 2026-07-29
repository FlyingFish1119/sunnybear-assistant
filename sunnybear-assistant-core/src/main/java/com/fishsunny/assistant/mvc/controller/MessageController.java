package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 05:13
 */

import com.fishsunny.assistant.dto.AssistantMessageEditDTO;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/message")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    private final ChatMessageService chatMessageService;

    @Autowired
    public MessageController(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    @RequestMapping("/history/get")
    public RestResponse getHistory(@RequestParam(required = false) String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            List<ChatMessage> history = chatMessageService.getConversationHistory(sessionId);
            return new RestResponse().success(history);
        } catch (Exception e) {
            log.error("获取历史记录失败: sessionId={}", sessionId, e);
            return new RestResponse().error("获取历史记录失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("消息 ID 不能为空");
        }
        try {
            chatMessageService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除历史记录失败: id={}", id, e);
            return new RestResponse().error("删除历史记录失败: " + e.getMessage());
        }
    }

    @RequestMapping("/branch/switch")
    public RestResponse switchBranch(@RequestParam("id") String id,
                                     @RequestParam("direction") String direction) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("消息 ID 不能为空");
        }
        if (!"left".equals(direction) && !"right".equals(direction)) {
            return new RestResponse().error("方向参数必须为 left 或 right");
        }
        try {
            int affected = chatMessageService.switchBranch(id, direction);

            return new RestResponse().success("已切换分支，更新 " + affected + " 条消息");
        } catch (Exception e) {
            log.error("切换分支失败: id={}, direction={}", id, direction, e);
            return new RestResponse().error("切换分支失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete/user")
    public RestResponse deleteUserMessage(@RequestParam("userMessageId") String userMessageId) {
        if (!StringUtils.hasText(userMessageId)) {
            return new RestResponse().error("消息 ID 不能为空");
        }
        try {
            chatMessageService.deleteUserMessageWithChildren(userMessageId);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除用户消息失败: userMessageId={}", userMessageId, e);
            return new RestResponse().error("删除用户消息失败: " + e.getMessage());
        }
    }

    @RequestMapping("/edit/assistant")
    public RestResponse editAssistantMessage(@RequestBody AssistantMessageEditDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getId())) {
            return new RestResponse().error("消息 ID 不能为空");
        }
        if (!StringUtils.hasText(dto.getContent())) {
            return new RestResponse().error("内容不能为空");
        }
        try {
            chatMessageService.editAssistantMessage(dto.getId(), dto.getContent());
            return new RestResponse().success("编辑成功");
        } catch (Exception e) {
            log.error("编辑助手消息失败: id={}", dto.getId(), e);
            return new RestResponse().error("编辑助手消息失败: " + e.getMessage());
        }
    }
}
