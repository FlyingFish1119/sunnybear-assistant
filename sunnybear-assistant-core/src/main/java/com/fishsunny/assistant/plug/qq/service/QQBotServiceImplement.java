package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ Bot 服务实现 —— 通过 ChatWebSocketProxy 复用 Web 端完整对话流程
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:45
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.plug.qq.config.QQBotOption;
import com.fishsunny.assistant.plug.qq.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QQBotServiceImplement implements QQBotService {

    private static final Logger log = LoggerFactory.getLogger(QQBotServiceImplement.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final QQBotOption qqBotOption;
    private final ChatWebSocketProxy webSocketProxy;

    public QQBotServiceImplement(RestTemplate restTemplate,
                                 ObjectMapper objectMapper,
                                 QQBotOption qqBotOption,
                                 ChatWebSocketProxy webSocketProxy) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.qqBotOption = qqBotOption;
        this.webSocketProxy = webSocketProxy;
    }

    @Override
    public ApiResponse handleAndReply(QQMessage msg) {
        if (msg == null || msg.getRawMessage() == null) {
            return null;
        }
        if (!"message".equals(msg.getPostType())) {
            return null;
        }

        Long senderId = msg.getUserId();
        String senderNick = msg.getSender() != null ? msg.getSender().getNickname() : "未知";
        String userId = senderId.toString();

        // 白名单校验
        if (qqBotOption.getReplayIds().isEmpty() || !qqBotOption.getReplayIds().contains(senderId)) {
            log.info("忽略非白名单消息: {}({})", senderNick, senderId);
            return null;
        }

        // 内置指令
        if (msg.getRawMessage().startsWith("/")) {
            return handleCommand(msg.getRawMessage(), userId, msg);
        }

        // 委托 ChatWebSocketProxy 执行完整 Web 对话流程，所有回复通过 callback 推送到 QQ
        try {
            webSocketProxy.processMessage(userId, senderNick, msg.getRawMessage(),
                    progress -> sendReply(msg, progress));
        } catch (Exception e) {
            log.error("Web 对话流程处理失败: {}", e.getMessage(), e);
            sendReply(msg, "抱歉，处理你的消息时出错了: " + e.getMessage());
        }
        return null;
    }

    private ApiResponse sendReply(QQMessage msg, String text) {
        if ("private".equals(msg.getMessageType())) {
            return doPost("/send_private_msg",
                    new SendMsgRequest().setUserId(msg.getUserId()).setMessage(text));
        } else if ("group".equals(msg.getMessageType())) {
            return doPost("/send_group_msg",
                    new SendMsgRequest().setGroupId(msg.getGroupId()).setMessage(text));
        }
        return null;
    }

    private ApiResponse handleCommand(String rawMessage, String userId, QQMessage msg) {
        String cmd = rawMessage.trim();
        if ("/clear".equals(cmd)) {
            webSocketProxy.clearSession(userId);
            return sendReply(msg, "对话历史已清除（新会话已创建）");
        }
        if ("/stop".equals(cmd)) {
            webSocketProxy.stop(userId);
            return sendReply(msg, "已停止生成");
        }
        return null;
    }

    // ==================== 查询信息 ====================

    @Override
    public LoginInfo getLoginInfo() {
        Map<String, Object> data = callApiForMap("/get_login_info");
        return data != null ? objectMapper.convertValue(data, LoginInfo.class) : null;
    }

    @Override
    public List<FriendInfo> getFriendList() {
        return callApiForList("/get_friend_list", FriendInfo.class);
    }

    @Override
    public List<GroupInfo> getGroupList() {
        return callApiForList("/get_group_list", GroupInfo.class);
    }

    @Override
    public List<GroupMemberInfo> getGroupMemberList(Long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return callApiForList("/get_group_member_list", GroupMemberInfo.class, params);
    }

    @Override
    public GroupMemberInfo getGroupMemberInfo(Long groupId, Long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        Map<String, Object> data = callApiForMap("/get_group_member_info", params);
        return data != null ? objectMapper.convertValue(data, GroupMemberInfo.class) : null;
    }

    // ==================== QQ API 调用 ====================

    private ApiResponse doPost(String path, SendMsgRequest request) {
        String url = qqBotOption.getApiUrl() + path;
        log.debug("QQ API -> POST {} | body: {}", url, request);
        try {
            ApiResponse resp = restTemplate.postForObject(url, request, ApiResponse.class);
            log.debug("QQ API <- {} | status={} | messageId={}",
                    path, resp != null ? resp.getStatus() : null, resp != null ? resp.getMessageId() : null);
            return resp;
        } catch (Exception e) {
            log.error("QQ API 调用失败 [{}]: {}", path, e.getMessage());
            return new ApiResponse().setStatus("failed").setRetcode(-1).setMsg(e.getMessage());
        }
    }

    private Map<String, Object> callApiForMap(String path) {
        return callApiForMap(path, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callApiForMap(String path, Map<String, Object> params) {
        String url = qqBotOption.getApiUrl() + path;
        try {
            Map<String, Object> resp = restTemplate.postForObject(url, params, Map.class);
            if (resp == null || !"ok".equals(resp.get("status"))) {
                log.warn("QQ API <- {} 失败: {}", path, resp);
                return null;
            }
            Object data = resp.get("data");
            return (data instanceof Map) ? (Map<String, Object>) data : null;
        } catch (Exception e) {
            log.error("QQ API 调用异常 [{}]: {}", path, e.getMessage());
            return null;
        }
    }

    private <T> List<T> callApiForList(String path, Class<T> itemType) {
        return callApiForList(path, itemType, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> callApiForList(String path, Class<T> itemType, Map<String, Object> params) {
        String url = qqBotOption.getApiUrl() + path;
        try {
            Map<String, Object> resp = restTemplate.postForObject(url, params, Map.class);
            if (resp == null || !"ok".equals(resp.get("status"))) {
                log.warn("QQ API <- {} 失败: {}", path, resp);
                return Collections.emptyList();
            }
            Object data = resp.get("data");
            if (data instanceof List) {
                return objectMapper.convertValue(data,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, itemType));
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("QQ API 调用异常 [{}]: {}", path, e.getMessage());
            return Collections.emptyList();
        }
    }
}
