package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储连接器的握手约定 —— 身份头 + 角色互补校验
 *
 * 连接方向不再代表身份，所以身份必须在握手时说清楚：谁发起连接、连到哪个地址都不重要，
 * 重要的是这条线两端一个 remote 一个 local。角色相同的两条线毫无意义——两边都等着对方执行，
 * 请求发出去没人接，只能干等到超时；所以在握手阶段就拒掉，让问题在配置这一层暴露出来。
 *
 * 鉴权不在这里做：发起方按每个目标 url 各自带 Basic 凭据，真正的校验交给对端 Nginx；
 * 这里只认角色，避免两头都在等对方执行那种配置错误。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/21
 */

public final class RepoRpcHandshake {

    /** 发起方声明自己的角色：remote 或 local */
    public static final String HEADER_ROLE = "X-Repo-Role";

    /** 发起方节点名，只用于日志与排查 */
    public static final String HEADER_NODE = "X-Repo-Node";

    /** 帧格式版本，两端必须一致；改帧结构时递增，避免两端版本错配后报一堆解析错误 */
    public static final String HEADER_PROTOCOL = "X-Repo-Protocol";

    public static final String PROTOCOL_VERSION = "1";

    /** 拦截器校验通过后把对端信息写进会话属性，端点建通道时取出来记日志 */
    public static final String ATTR_PEER_ROLE = "repoPeerRole";

    public static final String ATTR_PEER_NODE = "repoPeerNode";

    private RepoRpcHandshake() {
    }
}
