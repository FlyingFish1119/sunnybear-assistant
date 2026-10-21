package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储连接器 —— 唯一负责"有没有一条能用的线"的地方
 *
 * 它同时对两种行为负责：
 *   connect  起守护线程按地址列表连出去，断了按退避重连，连上以后把通道交给池子
 *   accept   自己不发连接，等端点把别人连进来的通道移交进来
 * 两种情况产出的都是同一种东西：一条双向可用的通道。谁发起连接对这个池子没有意义。
 *
 * 心跳只由发起方发、接受方回 pong。这样不管方向怎么翻，链路活性都有人负责；
 * 发起方靠 pong 判定半死连接（连续两个周期收不到任何帧就当它死了重连），
 * 接受方不主动判定，断了靠 afterConnectionClosed 感知 —— 和改造前的行为一致。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/21
 */

import com.fishsunny.assistant.remote.config.RemoteRepositoryProperties;
import com.fishsunny.assistant.remote.config.RemoteRepositoryProperties.Target;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RepoLink {

    /** 接收缓冲默认只有 8KB，分页查询这种大响应会被容器以 1009 直接关连接，跟服务端保持一致放到 50MB */
    private static final int MAX_MESSAGE_BUFFER_SIZE = 50 * 1024 * 1024;

    /** 发请求时最多等多久通道就绪：启动瞬间第一条连接可能还在握手中 */
    private static final long ACQUIRE_WAIT_MS = 5000L;

    private static final long ACQUIRE_POLL_MS = 50L;

    private static final long MIN_RECONNECT_DELAY_MS = 200L;

    private final RemoteRepositoryProperties properties;
    private final RepoRpcFrameHandler frameHandler;
    private final StandardWebSocketClient webSocketClient;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService worker;
    @Getter
    private volatile boolean closed;

    public RepoLink(RemoteRepositoryProperties properties, RepoRpcFrameHandler frameHandler) {
        this.properties = properties;
        this.frameHandler = frameHandler;
        this.webSocketClient = createWebSocketClient();
    }

    private static StandardWebSocketClient createWebSocketClient() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(MAX_MESSAGE_BUFFER_SIZE);
        container.setDefaultMaxBinaryMessageBufferSize(MAX_MESSAGE_BUFFER_SIZE);
        return new StandardWebSocketClient(container);
    }

    /** 启动连接器：需要主动连接时按目标地址各起一个守护循环，并把心跳挂上 */
    public void start() {
        if (!properties.initiatesConnection()) {
            log.info("仓储连接器就绪：role={} node={} behavior={}，不主动连接，只等对端接入",
                    properties.getRole(), properties.getNode(), properties.resolvedBehavior());
            if (properties.isRemote()) {
                log.info("当前是 role=remote + behavior={}：本机不主动连接，必须由对端（role=local）配 behavior=connect，"
                                + "并在 link.local-urls 里指向本机 /ws/repo，通道才会建立",
                        properties.resolvedBehavior());
            }
            return;
        }
        List<Target> targets = properties.connectTargets();
        if (targets.isEmpty()) {
            log.warn("仓储连接器未启动：behavior={} 需要主动连接，但 remote / local-urls 没有配目标",
                    properties.resolvedBehavior());
            return;
        }
        int poolSize = targets.size() + 1;
        worker = Executors.newScheduledThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "repo-link");
            thread.setDaemon(true);
            return thread;
        });
        for (Target target : targets) {
            worker.execute(() -> maintain(target));
        }
        long interval = properties.getKeepAliveIntervalMs() == null ? 0L : properties.getKeepAliveIntervalMs();
        if (interval > 0) {
            worker.scheduleWithFixedDelay(() -> heartbeat(interval), interval, interval, TimeUnit.MILLISECONDS);
        }
        log.info("仓储连接器启动：role={} node={} behavior={}，目标 {} 个 {}",
                properties.getRole(), properties.getNode(), properties.resolvedBehavior(), targets.size(),
                targets.stream().map(Target::normalizedUrl).toList());
    }

    /**
     * 被动接入的通道由端点移交进来。这里只登记，不猜对端是谁 —— local 无状态，谁来问都执行。
     */
    public void register(WebSocketSession session, String peerNode) {
        if (closed) {
            closeQuietly(session);
            return;
        }
        channels.put(session.getId(), new Channel(session, "in:" + peerNode, false));
        log.info("仓储通道已登记：对端 node={}，当前通道数 {}", peerNode, channels.size());
    }

    /**
     * 取一条能发请求的通道。启动瞬间通道可能还没连上，短暂等一会儿，
     * 等不到再抛异常 —— 把"连不上"和"调用失败"分开报，排查时一眼能看出是链路问题。
     */
    public WebSocketSession acquire() {
        long deadline = System.currentTimeMillis() + ACQUIRE_WAIT_MS;
        while (true) {
            WebSocketSession ready = firstOpenSession();
            if (ready != null) {
                return ready;
            }
            if (closed) {
                throw new RepoRpcException("仓储连接器已关闭");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new RepoRpcException(
                        "没有可用的仓储通道（role=" + properties.getRole()
                        + "，behavior=" + properties.resolvedBehavior()
                        + "），请检查对端是否可达、两端角色是否互补");
            }
            sleep(ACQUIRE_POLL_MS);
        }
    }

    /** 通道断开时剔出池子；池子里一条都不剩了才让在途请求失败，避免误伤其它通道上的调用 */
    public void invalidate(WebSocketSession session) {
        Channel removed = channels.remove(session.getId());
        if (removed == null) {
            return;
        }
        removed.detached.countDown();
        if (!hasOpenSession()) {
            frameHandler.failAll("仓储连接已断开，当前没有可用通道：" + removed.target);
        }
    }

    public void close() {
        closed = true;
        ScheduledExecutorService current = worker;
        if (current != null) {
            current.shutdownNow();
        }
        channels.values().forEach(channel -> {
            channel.detached.countDown();
            closeQuietly(channel.session);
        });
        channels.clear();
    }

    /**
     * 维护一个目标的连接：连上后阻塞到这条连接断开，再按退避重连。
     * 连上就把退避重置回初始值，避免偶发一次抖动之后永远退在长间隔上。
     */
    private void maintain(Target target) {
        String url = target.normalizedUrl();
        long delay = initialDelay();
        while (!closed) {
            Channel channel = null;
            try {
                channel = connect(target);
                log.info("仓储连接已建立：{}（本机 role={} node={}）",
                        url, properties.getRole(), properties.getNode());
                delay = initialDelay();
                channel.detached.await();
                if (closed) {
                    return;
                }
                log.warn("仓储连接已断开：{}", url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warn("仓储连接失败 {}：{}", url, t.getMessage());
            } finally {
                if (channel != null) {
                    channels.remove(channel.session.getId());
                }
            }
            if (closed) {
                return;
            }
            long wait = backoff(delay);
            log.info("仓储连接 {} ms 后重试：{}", wait, url);
            sleep(wait);
            delay = Math.min(delay * 2, maxDelay());
        }
    }

    private Channel connect(Target target) throws Exception {
        String url = target.normalizedUrl();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(RepoRpcHandshake.HEADER_ROLE, properties.getRole());
        headers.set(RepoRpcHandshake.HEADER_NODE, properties.getNode());
        headers.set(RepoRpcHandshake.HEADER_PROTOCOL, RepoRpcHandshake.PROTOCOL_VERSION);
        applyBasicAuth(headers, target);

        long timeoutMs = properties.getTimeoutMs() == null ? 15000L : properties.getTimeoutMs();
        WebSocketSession session = webSocketClient.execute(new LinkHandler(), headers, URI.create(url))
                .get(timeoutMs, TimeUnit.MILLISECONDS);

        Channel channel = new Channel(session, url, true);
        channels.put(session.getId(), channel);
        return channel;
    }

    /**
     * 每个目标用它自己那套凭据：对端 Nginx 是按站点配 Basic 的，多台对端不该共用一对账号。
     * 没配用户名或密码就不带 Authorization 头，留给外层（比如 Nginx 的 IP 白名单）去管。
     */
    private void applyBasicAuth(WebSocketHttpHeaders headers, Target target) {
        String username = target.getUsername();
        String password = target.getPassword();
        if (username == null || username.isBlank() || password == null) {
            return;
        }
        String token = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + token);
    }

    /** 只对发起方发心跳；接受方由端点回 pong，不在这里发 */
    private void heartbeat(long intervalMs) {
        long now = System.currentTimeMillis();
        for (Channel channel : channels.values()) {
            if (!channel.initiator) {
                continue;
            }
            WebSocketSession session = channel.session;
            if (!session.isOpen()) {
                invalidate(session);
                continue;
            }
            if (now - channel.lastFrameAt > intervalMs * 2) {
                log.warn("仓储连接两个心跳周期没有任何帧，判定半死并丢弃：{}", channel.target);
                invalidate(session);
                closeQuietly(session);
                continue;
            }
            try {
                sendQuietly(session, RepoRpcProtocol.KEEP_ALIVE);
            } catch (IOException e) {
                log.warn("仓储心跳发送失败 {}：{}", channel.target, e.getMessage());
                invalidate(session);
                closeQuietly(session);
            }
        }
    }

    private WebSocketSession firstOpenSession() {
        for (Channel channel : channels.values()) {
            WebSocketSession session = channel.session;
            if (session.isOpen()) {
                return session;
            }
        }
        return null;
    }

    private boolean hasOpenSession() {
        for (Channel channel : channels.values()) {
            if (channel.session.isOpen()) {
                return true;
            }
        }
        return false;
    }

    private void touch(WebSocketSession session) {
        Channel channel = channels.get(session.getId());
        if (channel != null) {
            channel.lastFrameAt = System.currentTimeMillis();
        }
    }

    /**
     * 写串行化：Spring 的 WebSocketSession 不是线程安全的，并发写会撞 TEXT_PARTIAL_WRITING。
     * 手法与 SynchronizedWebSocketSession 一致，所有写出口都从这里过。
     */
    static void sendQuietly(WebSocketSession session, String payload) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (IOException e) {
            log.debug("关闭仓储连接失败：{}", e.getMessage());
        }
    }

    private long initialDelay() {
        Long configured = properties.getLink() == null || properties.getLink().getReconnect() == null
                ? null : properties.getLink().getReconnect().getInitialDelayMs();
        return configured == null ? 3000L : Math.max(MIN_RECONNECT_DELAY_MS, configured);
    }

    private long maxDelay() {
        Long configured = properties.getLink() == null || properties.getLink().getReconnect() == null
                ? null : properties.getLink().getReconnect().getMaxDelayMs();
        return configured == null ? 60000L : Math.max(initialDelay(), configured);
    }

    /** ±20% 抖动：多台 local 同时掉线重连时不至于每轮都撞在同一秒 */
    private static long backoff(long delay) {
        double factor = 0.8 + Math.random() * 0.4;
        return Math.max(MIN_RECONNECT_DELAY_MS, (long) (delay * factor));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 池子里的一条通道；uncaught 的断开由 detached 通知守护循环去重连 */
    private static final class Channel {

        private final WebSocketSession session;
        private final String target;
        private final boolean initiator;
        private final CountDownLatch detached = new CountDownLatch(1);
        private volatile long lastFrameAt = System.currentTimeMillis();

        private Channel(WebSocketSession session, String target, boolean initiator) {
            this.session = session;
            this.target = target;
            this.initiator = initiator;
        }
    }

    /** 主动连接时挂上去的处理器；收到的帧一律交给帧处理器，心跳帧只用来刷新活跃时间 */
    private final class LinkHandler extends TextWebSocketHandler {

        @Override
        protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
            touch(session);
            String payload = message.getPayload();
            if (RepoRpcProtocol.KEEP_ALIVE.equals(payload)) {
                return;
            }
            String reply = frameHandler.onFrame(session, payload);
            if (reply != null) {
                try {
                    sendQuietly(session, reply);
                } catch (IOException e) {
                    log.warn("回写仓储 RPC 帧失败：{}", e.getMessage());
                }
            }
        }

        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
            log.warn("仓储连接已关闭：{}（{}）", session.getUri(), status);
            invalidate(session);
        }

        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            log.warn("仓储连接异常：{}（{}）", session.getUri(), exception.getMessage());
            invalidate(session);
        }
    }
}
