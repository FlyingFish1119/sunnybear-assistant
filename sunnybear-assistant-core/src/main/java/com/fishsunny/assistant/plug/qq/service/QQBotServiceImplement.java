package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ Bot 服务实现 —— 通过 ChatWebSocketProxy 复用 Web 端完整对话流程。
 *        段落切分由 ChatWebSocketProxy 按流式段边界（空行）完成，这里承担发送侧兜底：
 *        单段超长时按句末标点二次切分，多段之间留出连发节奏。
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
import java.util.function.Consumer;

@Service
public class QQBotServiceImplement implements QQBotService {

    private static final Logger log = LoggerFactory.getLogger(QQBotServiceImplement.class);

    /** 段落边界：空行。与 ChatWebSocketProxy 的流式段边界同一规则，两条发送路径分段一致 */
    private static final String SEGMENT_BOUNDARY_REGEX = "\\r?\\n[ \\t]*\\r?\\n";

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
                    progress -> sendReplySegmented(msg, progress));
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

    // ==================== 主动发送（工具调用） ====================

    @Override
    public int sendPrivateMessage(Long userId, String message) {
        return sendSegmented(segment -> doPost("/send_private_msg",
                new SendMsgRequest().setUserId(userId).setMessage(segment)), message);
    }

    @Override
    public int sendGroupMessage(Long groupId, String message) {
        return sendSegmented(segment -> doPost("/send_group_msg",
                new SendMsgRequest().setGroupId(groupId).setMessage(segment)), message);
    }

    // ==================== 发送分段 ====================

    /** 对话回复：按消息来源回发，分段规则与主动发送一致 */
    private void sendReplySegmented(QQMessage msg, String text) {
        sendSegmented(segment -> sendReply(msg, segment), text);
    }

    /**
     * 分段发送：按空行切段，超长段落再按句末标点兜底切分，段间留出连发间隔。
     * 流式分片与工具的整段文本都走这里，保证两条路径的分段与节奏一致。
     *
     * @return 实际发送的条数
     */
    private int sendSegmented(Consumer<String> sender, String text) {
        List<String> segments = splitSegments(text);
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sleepQuietly(SEGMENT_INTERVAL_MS);
            }
            sender.accept(segments.get(i));
        }
        return segments.size();
    }

    /** 整段文本 → 若干条待发消息：先按空行分段，逐段再做长度兜底 */
    private List<String> splitSegments(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        for (String block : text.split(SEGMENT_BOUNDARY_REGEX)) {
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
