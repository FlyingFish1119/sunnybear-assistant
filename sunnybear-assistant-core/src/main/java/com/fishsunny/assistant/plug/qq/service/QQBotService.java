package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ Bot 服务接口（OneBot v11 标准）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:45
 */

import com.fishsunny.assistant.plug.qq.entity.*;

import java.util.List;

public interface QQBotService {

    /** 接收消息并处理回复 */
    ApiResponse handleAndReply(QQMessage message);

    // ==================== 主动发送（工具调用，非消息回复） ====================

    /**
     * 主动向指定好友发送消息，文本按空行分段、超长按句末标点兜底，逐条发出。
     *
     * @param userId  接收者 QQ 号
     * @param message 消息内容
     * @return 实际发送的条数
     */
    int sendPrivateMessage(Long userId, String message);

    /**
     * 主动向指定群发送消息，分段规则同上。
     *
     * @param groupId 群号
     * @param message 消息内容
     * @return 实际发送的条数
     */
    int sendGroupMessage(Long groupId, String message);

    // ==================== 查询信息 ====================

    LoginInfo getLoginInfo();

    List<FriendInfo> getFriendList();

    List<GroupInfo> getGroupList();

    List<GroupMemberInfo> getGroupMemberList(Long groupId);

    GroupMemberInfo getGroupMemberInfo(Long groupId, Long userId);
}
