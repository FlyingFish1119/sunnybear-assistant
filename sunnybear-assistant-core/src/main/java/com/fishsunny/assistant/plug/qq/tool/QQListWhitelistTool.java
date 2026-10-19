package com.fishsunny.assistant.plug.qq.tool;

/*
 * @Usage QQ 白名单查询工具 —— 列出可主动发送的 QQ 号
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/19
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.qq.config.QQBotOption;
import com.fishsunny.assistant.plug.qq.entity.FriendInfo;
import com.fishsunny.assistant.plug.qq.service.QQBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@ToolKitComponent(QQToolKit.class)
@ConditionalOnExpression("${plug.qq.tool.enable:true} && ${plug.qq.tool.list_whitelist.enable:true}")
public class QQListWhitelistTool implements ToolHandler {

    public static final String NAME = "qq_list_whitelist";

    private final ToolRegister register;
    private final QQBotService qqBotService;
    private final QQBotOption qqBotOption;

    public QQListWhitelistTool(QQBotService qqBotService, QQBotOption qqBotOption) {
        this.qqBotService = qqBotService;
        this.qqBotOption = qqBotOption;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        列出当前允许主动发送 QQ 消息的白名单（配置项 plug.qq.bot.replay-ids），并尽力从好友列表匹配昵称。
                        用于确认消息可以发给谁，以及私聊工具不填 userId 时默认发给哪个号。
                        只读，不会发出任何消息。""")
                .setRequired(List.of())
                .setParameters(List.of());
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        List<Long> replayIds = qqBotOption.getReplayIds();
        if (replayIds == null || replayIds.isEmpty()) {
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "白名单为空（plug.qq.bot.replay-ids 未配置），当前没有可发送的对象，也没有默认收件人。");
        }

        Map<Long, FriendInfo> friends = fetchFriends();
        StringBuilder sb = new StringBuilder();
        sb.append("白名单共 ").append(replayIds.size()).append(" 个 QQ 号（plug.qq.bot.replay-ids）：\n");
        for (int i = 0; i < replayIds.size(); i++) {
            Long userId = replayIds.get(i);
            sb.append("- ").append(userId);
            String name = displayName(friends.get(userId));
            if (name != null) {
                sb.append("（").append(name).append("）");
            }
            if (i == 0) {
                sb.append("  ← 未指定 userId 时私聊默认发往这个号");
            }
            sb.append("\n");
        }
        if (friends.isEmpty()) {
            sb.append("\n注：未能取到好友列表，仅显示 QQ 号（OneBot 未连接或接口查询失败都不影响发送本身）。");
        }
        return new ToolExecutor.ToolExecuteResponse(NAME, sb.toString().stripTrailing());
    }

    /** 备注优先于昵称；两者都没有则返回 null */
    private String displayName(FriendInfo friend) {
        if (friend == null) {
            return null;
        }
        if (StringUtils.hasText(friend.getRemark())) {
            return friend.getRemark();
        }
        return StringUtils.hasText(friend.getNickname()) ? friend.getNickname() : null;
    }

    /** 尽力取好友列表补昵称；取不到就退化为只列号码，不影响工具可用性 */
    private Map<Long, FriendInfo> fetchFriends() {
        try {
            List<FriendInfo> friends = qqBotService.getFriendList();
            if (friends == null || friends.isEmpty()) {
                return Map.of();
            }
            return friends.stream()
                    .filter(friend -> friend.getUserId() != null)
                    .collect(Collectors.toMap(FriendInfo::getUserId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("获取 QQ 好友列表失败，白名单工具退化为只列号码: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }
}
