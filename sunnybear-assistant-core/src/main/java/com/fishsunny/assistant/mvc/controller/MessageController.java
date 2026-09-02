package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 05:13
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.AssistantMessageEditDTO;
import com.fishsunny.assistant.dto.ChatExportFileData;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatExportService;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/message")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    /** 导出文件名时间戳格式 */
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final ChatExportService chatExportService;
    private final ObjectMapper objectMapper;

    @Autowired
    public MessageController(ChatMessageService chatMessageService,
                             ChatSessionService chatSessionService,
                             ChatExportService chatExportService,
                             ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.chatSessionService = chatSessionService;
        this.chatExportService = chatExportService;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出会话对话记录为文件下载（markdown 默认，支持 text/json）。
     * 聊天 / 角色扮演 / 世界群聊会话共用同一套消息存储，均可导出。
     * 成功返回附件下载；失败返回 JSON 错误体（{status, message}）。
     *
     * @param sessionId 会话 ID
     * @param format    导出格式：markdown(md) / text(txt) / json，null 或未知按 markdown
     */
    @RequestMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam("sessionId") String sessionId,
                                         @RequestParam(value = "format", required = false) String format) {
        if (!StringUtils.hasText(sessionId)) {
            return errorResponse(HttpStatus.BAD_REQUEST, "会话 ID 不能为空");
        }
        try {
            ChatExportFileData fileData = chatExportService.export(sessionId, format);
            ChatSession session = chatSessionService.findById(sessionId);
            String name = session != null && StringUtils.hasText(session.getName()) ? session.getName() : "对话";
            String filename = sanitizeFileName(name) + "_" + LocalDateTime.now().format(FILE_TIME) + "." + fileData.getExt();
            byte[] bytes = fileData.getContent().getBytes(StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(fileData.getMime() + "; charset=UTF-8"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            log.warn("导出会话参数错误: sessionId={}, format={}, {}", sessionId, format, e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("导出会话失败: sessionId={}", sessionId, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败: " + e.getMessage());
        }
    }

    /**
     * 构造统一 JSON 错误响应体（与前端 API 封装的 {status, message} 结构一致）
     */
    private ResponseEntity<byte[]> errorResponse(HttpStatus status, String message) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(new RestResponse().error(message));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<>(body, headers, status);
        } catch (Exception e) {
            log.error("序列化错误响应失败", e);
            return ResponseEntity.status(status).build();
        }
    }

    /**
     * 清洗导出文件名中的非法字符，保证可安全保存
     */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
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
