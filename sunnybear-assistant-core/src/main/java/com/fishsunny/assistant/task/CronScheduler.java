package com.fishsunny.assistant.task;

/*
 * @Usage 定时任务调度器 —— 启动时加载 DB 中的 cron 任务，动态注册/取消，触发时走 WebSocket handler 流程
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.mvc.dao.CronJobRepository;
import com.fishsunny.assistant.remote.RepoLink;
import com.fishsunny.assistant.websocket.ws.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.ws.NoOpWebSocketSession;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class CronScheduler {

    private final TaskScheduler taskScheduler;
    private final CronJobRepository cronJobRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final RepoLink repoLink;

    /** 存储模式：remote 表示本实例是外挂端（库在远端），单点任务不在本机跑 */
    private final String storageRole;

    /** 已调度的任务：cronJobId → ScheduledFuture */
    private final Map<Integer, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CronScheduler(TaskScheduler taskScheduler,
                         CronJobRepository cronJobRepository,
                         @Lazy ChatWebSocketHandler chatWebSocketHandler,
                         ObjectMapper objectMapper,
                         @Value("${assistant.remote-storage.role:local}") String storageRole,
                         RepoLink repoLink) {
        this.taskScheduler = taskScheduler;
        this.cronJobRepository = cronJobRepository;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.objectMapper = objectMapper;
        this.storageRole = storageRole;
        this.repoLink = repoLink;
    }

    /**
     * 启动时加载所有 cron 任务并注册。
     * <p>role=remote 时仓储在远端，启动这一刻通道可能还没建立（behavior=accept 还得等对端连进来）。
     * 这里绝不能把异常抛出去 —— @PostConstruct 抛异常会让整个 Spring 上下文起不来。
     * 拿不到就先放行，交给后台等通道就绪后补加载。
     */
    @PostConstruct
    public void init() {
        if (loadJobs()) {
            return;
        }
        log.info("定时任务暂不加载：等仓储通道就绪后再补（不阻塞启动）");
        Thread retry = new Thread(this::loadWhenChannelReady, "cron-init-wait");
        retry.setDaemon(true);
        retry.start();
    }

    /** 加载一次 DB 里的全部任务；远程仓储还没连上时返回 false，由调用方决定要不要重试 */
    private boolean loadJobs() {
        List<CronJob> jobs;
        try {
            jobs = cronJobRepository.selectAll();
        } catch (Exception e) {
            log.warn("定时任务加载失败，稍后重试：{}", e.getMessage());
            return false;
        }
        log.info("CronScheduler 启动，加载 {} 个定时任务", jobs.size());
        for (CronJob job : jobs) {
            scheduleJob(job);
        }
        return true;
    }

    /** 等通道就绪后补加载，成功一次就退出；连接器关闭（应用停止）时一并退出 */
    @SuppressWarnings("resource")
    private void loadWhenChannelReady() {
        while (!repoLink.isClosed()) {
            try {
                if (repoLink.acquire() != null && loadJobs()) {
                    log.info("仓储通道已就绪，定时任务补加载完成");
                    return;
                }
            } catch (Exception e) {
                log.error("定时任务加载失败：{}", e.getMessage());
            }
        }
    }

    /** 注册一个 cron 任务（新增或更新后调用） */
    public void scheduleJob(CronJob job) {
        cancelJob(job.getId()); // 先取消旧的

        // 单点任务只在 mode=local 的实例上跑；remote 端连调度都不注册，免得空转触发
        if (Boolean.TRUE.equals(job.getLocalOnly()) && isRemoteStorage()) {
            log.info("跳过单点任务调度（当前为 remote 模式，只在 local 实例执行）: id={}, title={}", job.getId(), job.getTitle());
            return;
        }

        try {
            CronTrigger trigger = new CronTrigger(job.getCron(), TimeZone.getTimeZone(ZoneId.systemDefault()));
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeJob(job), trigger);
            scheduledTasks.put(job.getId(), future);
            log.info("已调度定时任务: id={}, title={}, cron={}", job.getId(), job.getTitle(), job.getCron());
        } catch (Exception e) {
            log.error("调度定时任务失败: id={}, cron={}, error={}", job.getId(), job.getCron(), e.getMessage());
        }
    }

    /** 取消一个 cron 任务（删除或更新前调用） */
    public void cancelJob(Integer id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
            log.info("已取消定时任务: id={}", id);
        }
    }

    /** 当前实例的存储模式是否为 remote（remote 端不跑单点任务） */
    private boolean isRemoteStorage() {
        return "remote".equalsIgnoreCase(storageRole);
    }

    /** cron 触发时执行：构建 ChatMessageRequest → 走 WebSocket handler 流程 */
    private void executeJob(CronJob job) {
        log.info("定时任务触发: id={}, title={}, cron={}", job.getId(), job.getTitle(), job.getCron());
        try {
            ChatMessageRequest request = new ChatMessageRequest()
                    .setMode(ChatMessageRequest.MODE_CREATE)
                    .setCronId(job.getId())
                    .setContent(job.getMessage());

            String payload = objectMapper.writeValueAsString(request);
            WebSocketSession noOpSession = new NoOpWebSocketSession("cron-" + job.getId());
            chatWebSocketHandler.processMessage(noOpSession, new TextMessage(payload));

            log.info("定时任务执行完成: id={}, title={}", job.getId(), job.getTitle());
        } catch (Exception e) {
            log.error("定时任务执行失败: id={}, title={}, error={}",
                    job.getId(), job.getTitle(), e.getMessage(), e);
        }
    }
}
