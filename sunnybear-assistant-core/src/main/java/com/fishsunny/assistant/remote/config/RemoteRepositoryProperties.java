package com.fishsunny.assistant.remote.config;

/*
 * @Usage 远程仓储配置 —— 把"我是谁"和"我怎么连"拆成两个正交的维度
 *
 * role 决定数据流向：remote 发请求，local 执行 SQL。这是唯一决定谁调谁靠什么代码的东西，
 * 跟连接是谁拉起来的一毛钱关系都没有。
 * link.behavior 决定连接器动作：connect 主动连出去、accept 等着被连、both 两样都干。
 *
 * 所以四种组合都能跑：
 *   remote + connect  以前的行为：本地发起连接，连到云端 /ws/repo
 *   remote + accept   云端发起连接，连到本地；本地口径不变，照样是 remote 发请求
 *   local  + accept   以前的服务端：谁连进来就在这条线上执行 SQL
 *   local  + connect  local 主动向多台 remote 提供服务，local-urls 每项一条独立连接
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "assistant.remote-storage")
public class RemoteRepositoryProperties {

    public static final String ROLE_LOCAL = "local";
    public static final String ROLE_REMOTE = "remote";

    public static final String BEHAVIOR_CONNECT = "connect";
    public static final String BEHAVIOR_ACCEPT = "accept";
    public static final String BEHAVIOR_BOTH = "both";

    /** 本机角色：local 执行 SQL（权威库），remote 把仓储调用发出去 */
    private String role = ROLE_LOCAL;

    /** 节点名，随握手头发给对端，日志里也带上，两台机器配置对不齐时靠它定位 */
    private String node = "application";

    /** 单次 RPC 超时（毫秒） */
    private Long timeoutMs = 15000L;

    /** 心跳间隔（毫秒），只由发起连接的一方定时发送；<=0 表示关闭心跳 */
    private Long keepAliveIntervalMs = 30000L;

    private Link link = new Link();

    @Data
    public static class Link {

        /** connect / accept / both；留空时按 role 推断：remote 默认 connect，local 默认 accept */
        private String behavior;

        /**
         * role=remote 且需要主动连接时的目标（对端是 local）。
         * 权威库只有一份，所以这里是单个目标，不是一个列表。
         */
        private Target remote = new Target();

        /**
         * role=local 且需要主动连接时的目标（对端是 remote）。
         * 同时向多台 remote 提供服务，每项一条独立连接，各自带各自的凭据。
         */
        private List<Target> localUrls = new ArrayList<>();

        private Reconnect reconnect = new Reconnect();
    }

    /** 一个连接目标：地址加它专属的 Nginx Basic 凭据 */
    @Data
    public static class Target {

        /** 目标地址，可以只写 host:port，/ws/repo 会由 normalizeUrl 补上 */
        private String url;

        /** 对端 Nginx 的 Basic 用户名；留空则握手时不带 Authorization 头 */
        private String username;

        /** 对端 Nginx 的 Basic 密码 */
        private String password;

        public String normalizedUrl() {
            return normalizeUrl(url);
        }
    }

    @Data
    public static class Reconnect {

        /** 首次重连间隔（毫秒） */
        private Long initialDelayMs = 3000L;

        /** 退避上限（毫秒） */
        private Long maxDelayMs = 60000L;
    }

    public boolean isRemote() {
        return ROLE_REMOTE.equalsIgnoreCase(trimmed(role));
    }

    public boolean isLocal() {
        return !isRemote();
    }

    /** 对端应该扮演的角色：local 的对端必须是 remote，反之亦然 */
    public String oppositeRole() {
        return isRemote() ? ROLE_LOCAL : ROLE_REMOTE;
    }

    /** 是否需要在本机注册 /ws/repo 端点等着被连 */
    public boolean acceptsConnection() {
        String behavior = resolvedBehavior();
        return BEHAVIOR_ACCEPT.equals(behavior) || BEHAVIOR_BOTH.equals(behavior);
    }

    /** 是否需要主动连出去 */
    public boolean initiatesConnection() {
        String behavior = resolvedBehavior();
        return BEHAVIOR_CONNECT.equals(behavior) || BEHAVIOR_BOTH.equals(behavior);
    }

    /**
     * 生效的连接行为。留空时按角色给默认值 —— remote 不主动连就没法发请求，
     * local 不等着被连就没法提供服务，这两个默认值刚好是各自唯一说得通的选项。
     */
    public String resolvedBehavior() {
        String configured = link == null ? null : link.getBehavior();
        if (!StringUtils.hasText(configured)) {
            return isRemote() ? BEHAVIOR_CONNECT : BEHAVIOR_ACCEPT;
        }
        return configured.trim().toLowerCase(Locale.ROOT);
    }

    /** 需要主动连接的目标，每项自带它那套 Nginx 凭据；role 不同取不同配置项 */
    public List<Target> connectTargets() {
        if (!initiatesConnection() || link == null) {
            return List.of();
        }
        if (isRemote()) {
            Target remote = link.getRemote();
            return remote != null && StringUtils.hasText(remote.getUrl()) ? List.of(remote) : List.of();
        }
        List<Target> configured = link.getLocalUrls();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        return configured.stream()
                .filter(target -> target != null && StringUtils.hasText(target.getUrl()))
                .toList();
    }

    /** 允许配置里只写 host:port，端点路径统一在这里补 */
    public static String normalizeUrl(String url) {
        String trimmed = trimmed(url);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/ws/repo") ? trimmed : trimmed + "/ws/repo";
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
