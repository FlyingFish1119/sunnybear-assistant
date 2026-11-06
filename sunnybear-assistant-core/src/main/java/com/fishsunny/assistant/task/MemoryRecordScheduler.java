package com.fishsunny.assistant.task;

/*
 * @Usage 自动记忆整理调度器
 *
 *        自己持有一个单线程调度线程，不占用 CronScheduler 共用的 TaskScheduler ——
 *        那个是单线程的，cron 跑模型时已经会把它占住，再叠一层长任务会把所有定时任务一起卡死。
 *
 *        待办表是「跑完即删」的：到点执行一次，执行方用删行认领；删不到说明已被别处拿走或期间被续期。
 *        到期后若发现 due_time 已被推后（用户又说话了），按新时间重排，不执行。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.SessionMemoryJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MemoryRecordScheduler {

    private final MemoryRecordService memoryRecordService;

    /** 会话 id → 待触发的定时器 */
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /** 单线程：整理要调模型、耗时不定，串行执行反而稳，也避免同时打爆模型额度 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-record");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryRecordScheduler(MemoryRecordService memoryRecordService) {
        this.memoryRecordService = memoryRecordService;
    }

    /** 续期并重排：由每一轮对话调用；开关关闭 / 非 chat 会话时 service 返回 null，这里什么都不做 */
    public void touch(ChatSession chatSession) {
        SessionMemoryJob job = memoryRecordService.touch(chatSession);
        if (job != null) {
            schedule(job);
        }
    }

    /** 启动扫描：等应用完全就绪（建表脚本、各 Repository 都已装配）后重建定时器 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() {
        try {
            List<SessionMemoryJob> jobs = memoryRecordService.listAll();
            log.info("自动记忆调度器启动：加载 {} 条待办", jobs.size());
            jobs.forEach(this::schedule);
        } catch (Exception e) {
            log.warn("自动记忆调度器启动扫描失败: {}", e.getMessage(), e);
        }
    }

    /** 按到期时间排一个定时器；同一会话的旧定时器先取消 */
    private void schedule(SessionMemoryJob job) {
        if (job == null || job.getSessionId() == null) {
            return;
        }
        cancel(job.getSessionId());
        long delayMillis = job.getDueTime() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), job.getDueTime()).toMillis());
        scheduled.put(job.getSessionId(), executor.schedule(
                () -> run(job.getSessionId()), delayMillis, TimeUnit.MILLISECONDS));
    }

    private void cancel(String sessionId) {
        ScheduledFuture<?> future = scheduled.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 到点执行；执行前重读待办，期间被续期或已删掉的一律以库里为准 */
    private void run(String sessionId) {
        scheduled.remove(sessionId);
        try {
            SessionMemoryJob latest = memoryRecordService.find(sessionId);
            if (latest == null) {
                return;
            }
            if (latest.getDueTime() != null && latest.getDueTime().isAfter(LocalDateTime.now())) {
                schedule(latest);
                return;
            }
            memoryRecordService.execute(sessionId);
        } catch (Exception e) {
            log.warn("自动记忆调度执行异常 [{}]: {}", sessionId, e.getMessage(), e);
        }
    }
}
