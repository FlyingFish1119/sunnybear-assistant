package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ Bot 服务实现 —— 通过 ChatWebSocketProxy 复用 Web 端完整对话流程。
 *        回复切分以 ChatWebSocketProxy 的流式分片为主，这里承担发送侧兜底：
 *        残留记号再切一次、单片超长按句末标点二次切分、多段之间留出连发节奏。
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QQBotServiceImplement implements QQBotService {

    private static final Logger log = LoggerFactory.getLogger(QQBotServiceImplement.class);

    /** 分条记号兜底：正常分片已由 ChatWebSocketProxy 按流式切好，这里再兜一次，防记号漏到 QQ 正文里 */
    private static final String SEGMENT_MARK_REGEX = "(?m)^[ \\t]*%{3}[ \\t]*\\r?$";

    /** 单条消息字符数兜底上限：超过则按句末标点二次切分（QQ 单条消息过长会被截断或拒收） */
    private static final int MAX_SEGMENT_LENGTH = 1500;

    /** 同一片被长度兜底切成多段时的段间间隔毫秒数，避免瞬间刷屏 */
    private static final long SEGMENT_INTERVAL_MS = 400L;

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
                    progress -> sendSegments(msg, progress));
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

    // ==================== 回复分条 ====================

    /**
     * 发送一片 AI 回复：可能来自流式分片，也可能是轮次结束时补发的整段。
     * 先按残留记号拆一次（兜底），再对仍超长的段落做句末标点二次切分，最后依次发出。
     * 模型按语义给出断点，程序按长度兜底，两层叠加避免断在半句话上。
     */
    private void sendSegments(QQMessage msg, String text) {
        List<String> segments = splitSegments(text);
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sleepQuietly(SEGMENT_INTERVAL_MS);
            }
            sendReply(msg, segments.get(i));
        }
    }

    private List<String> splitSegments(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        for (String block : text.split(SEGMENT_MARK_REGEX)) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            segments.addAll(limitLength(trimmed, MAX_SEGMENT_LENGTH));
        }
        return segments;
    }

    /** 把超长段落按句末标点贪心切成若干段，尽量不切断句子 */
    private List<String> limitLength(String segment, int limit) {
        List<String> result = new ArrayList<>();
        String remaining = segment;
        while (remaining.length() > limit) {
            int cut = findCutPoint(remaining, limit);
            String head = remaining.substring(0, cut).strip();
            remaining = remaining.substring(cut).strip();
            if (!head.isEmpty()) {
                result.add(head);
            }
        }
        if (!remaining.isEmpty()) {
            result.add(remaining);
        }
        return result;
    }

    /** 在 [limit/2, limit) 内自后向前找最近的断句位置；找不到就硬切在 limit 处 */
    private int findCutPoint(String text, int limit) {
        for (int i = limit - 1; i > limit / 2; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }
        return limit;
    }

    private boolean isSentenceEnd(char c) {
        return c == '\n' || c == '。' || c == '！' || c == '？' || c == '；' || c == '…'
                || c == '.' || c == '!' || c == '?' || c == ';';
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
