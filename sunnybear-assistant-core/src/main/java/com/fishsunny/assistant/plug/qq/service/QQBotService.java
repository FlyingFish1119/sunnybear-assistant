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

    // ==================== 查询信息 ====================

    LoginInfo getLoginInfo();

    List<FriendInfo> getFriendList();

    List<GroupInfo> getGroupList();

    List<GroupMemberInfo> getGroupMemberList(Long groupId);

    GroupMemberInfo getGroupMemberInfo(Long groupId, Long userId);
}
