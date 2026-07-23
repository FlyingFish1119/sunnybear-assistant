package com.fishsunny.assistant.plug.qq.controller;

/*
 * @Usage QQ 消息控制器（OneBot v11 标准）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 14:58
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.qq.entity.*;
import com.fishsunny.assistant.plug.qq.service.QQBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/qq")
public class QQController {

    private static final Logger log = LoggerFactory.getLogger(QQController.class);

    private final QQBotService qqBotService;

    public QQController(QQBotService qqBotService) {
        this.qqBotService = qqBotService;
    }

    // ==================== 接收 & 回复 ====================

    /**
     * 接收 QQ 消息（OneBot HTTP 上报回调），处理后自动回复。
     * 由 NapCat / LLOneBot 等框架主动推送。
     */
    @PostMapping("/message/get")
    public void receiveMessage(@RequestBody QQMessage message) {
        String type = message.getMessageType();
        Long senderId = message.getUserId();
        String senderNick = message.getSender() != null ? message.getSender().getNickname() : "未知";

        log.info("收到QQ消息 [{}] {}({}) | postType={} | text: {}",
                type, senderNick, senderId, message.getPostType(), message.getRawMessage());

        qqBotService.handleAndReply(message);
    }

    // ==================== 查询接口 ====================

    @GetMapping("/login/info")
    public RestResponse getLoginInfo() {
        LoginInfo info = qqBotService.getLoginInfo();
        return new RestResponse().success(info);
    }

    @GetMapping("/friend/list")
    public RestResponse getFriendList() {
        List<FriendInfo> list = qqBotService.getFriendList();
        return new RestResponse().success(list);
    }

    @GetMapping("/group/list")
    public RestResponse getGroupList() {
        List<GroupInfo> list = qqBotService.getGroupList();
        return new RestResponse().success(list);
    }

    @GetMapping("/group/member/list")
    public RestResponse getGroupMemberList(@RequestParam Long groupId) {
        List<GroupMemberInfo> list = qqBotService.getGroupMemberList(groupId);
        return new RestResponse().success(list);
    }

    @GetMapping("/group/member/info")
    public RestResponse getGroupMemberInfo(@RequestParam Long groupId, @RequestParam Long userId) {
        GroupMemberInfo info = qqBotService.getGroupMemberInfo(groupId, userId);
        return new RestResponse().success(info);
    }
}
