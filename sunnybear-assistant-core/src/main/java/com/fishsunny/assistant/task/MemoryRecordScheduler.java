package com.fishsunny.assistant.task;

/*
 * @Usage 自动记忆整理调度器（轮询模式）
 *
 *        待办表就是唯一真相：谁想让一段对话被整理，就往库里写一行 due_time；
 *        这个调度器只在 role=local（权威库这一侧）按固定间隔捞已到期的行，逐条执行。
 *        执行前靠「删行认领」抢占，删到 0 行说明已被别处拿走或期间又续期了，直接跳过。
 *
 *        不为每个会话维护内存定时器：重启、多实例、角色切换都不需要重建任何东西，
 *        轮询第一拍自然就把库里该做的接上了。
 *
 *        自己持有一个单线程调度线程，不占用 CronScheduler 共用的 TaskScheduler ——
 *        那个是单线程的，cron 跑模型时已经会把它占住，再叠一层长任务会把所有定时任务一起卡死。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.SessionMemoryJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MemoryRecordScheduler {

    /**
     * 轮询间隔：1 小时。
     * <p>会话最短的归档时间就是 1 小时，再快也没有可捞的；查询走 due_time 索引，空跑只是毫秒级。
     */
    private static final long POLL_INTERVAL_SECONDS = 3600;

    private final MemoryRecordService memoryRecordService;

    /** 轮询只在权威库这一侧跑；remote 实例连线程都不开，避免空转和多余的 RPC */
    private final boolean localStorage;

    /** 单线程：整理要调模型、耗时不定，串行执行反而稳，也避免同时打爆模型额度 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-record");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryRecordScheduler(MemoryRecordService memoryRecordService,
                                 @Value("${assistant.remote-storage.role:local}") String storageRole) {
        this.memoryRecordService = memoryRecordService;
        this.localStorage = !"remote".equalsIgnoreCase(storageRole);
    }

    /** 等应用完全就绪（建表脚本、各 Repository 都已装配）后再起轮询线程 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!localStorage) {
            log.info("自动记忆调度器：remote 角色，不启动轮询");
            return;
        }
        // 第一拍立即执行，把停机期间已经到期的补上；之后每小时一拍
        executor.scheduleWithFixedDelay(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 续期：由每一轮真实对话调用，只写库不排定时器；开关关闭 / 非 chat 会话时 service 内部直接返回 */
    public void touch(ChatSession chatSession) {
        memoryRecordService.touch(chatSession);
    }

    /**
     * 捞出到期的待办逐条执行。
     * <p>异常必须全部吞在内部：scheduleWithFixedDelay 的任务一旦抛出去，后续调度会被直接取消。
     */
    private void poll() {
        try {
            List<SessionMemoryJob> jobs = memoryRecordService.findDue();
            for (SessionMemoryJob job : jobs) {
                try {
                    memoryRecordService.execute(job.getSessionId());
                } catch (Exception e) {
                    // 单条失败不影响同批其它待办
                    log.warn("自动记忆执行异常 [{}]: {}", job.getSessionId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("自动记忆轮询失败: {}", e.getMessage(), e);
        }
    }
}
