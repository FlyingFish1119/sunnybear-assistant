package com.fishsunny.assistant.cron;

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
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.NoOpWebSocketSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final TaskScheduler taskScheduler;
    private final CronJobRepository cronJobRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ObjectMapper objectMapper;

    /** 已调度的任务：cronJobId → ScheduledFuture */
    private final Map<Integer, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CronScheduler(TaskScheduler taskScheduler,
                         CronJobRepository cronJobRepository,
                         @Lazy ChatWebSocketHandler chatWebSocketHandler,
                         ObjectMapper objectMapper) {
        this.taskScheduler = taskScheduler;
        this.cronJobRepository = cronJobRepository;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    /** 启动时加载所有 cron 任务并注册 */
    @PostConstruct
    public void init() {
        List<CronJob> jobs = cronJobRepository.selectAll();
        log.info("CronScheduler 启动，加载 {} 个定时任务", jobs.size());
        for (CronJob job : jobs) {
            scheduleJob(job);
        }
    }

    /** 注册一个 cron 任务（新增或更新后调用） */
    public void scheduleJob(CronJob job) {
        cancelJob(job.getId()); // 先取消旧的

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
