package com.fishsunny.assistant.task;

/*
 * @Usage 每隔一天凌晨定时刷新问候语：先删除创建时间超过 7 天的旧问候语，
 *        再为所有时间段（上午/中午/下午/晚上/深夜）各生成 3 条新问候语，
 *        生成时附带核心记忆，使问候语更贴合用户
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/16
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;
import com.fishsunny.assistant.mvc.service.AiGreetingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class GreetingRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(GreetingRefreshTask.class);

    /** 问候语保留天数，超过该天数的旧问候语会被删除 */
    @Value("${assistant.greeting.expire-day:7}")
    private int expireDay;

    private final AiGreetingService aiGreetingService;

    public GreetingRefreshTask(AiGreetingService aiGreetingService) {
        this.aiGreetingService = aiGreetingService;
    }

    /**
     * 每隔一天的 5:30 执行（赶在 6 点"上午"时段开始前刷新）
     */
    @Scheduled(cron = "0 30 5 * * *")
    public void refreshGreetings() {
        log.info("问候语定时刷新开始");
        try {
            // 1. 清理 7 天前的旧问候语
            int deleted = aiGreetingService.deleteBefore(LocalDateTime.now().minusDays(expireDay));
            log.info("已清理 {} 条超过 {} 天的旧问候语", deleted, expireDay);

            // 2. 重新生成各时段问候语（每时段 3 条）
            List<AiGreeting> greetings = aiGreetingService.generateGreeting();
            log.info("问候语定时刷新完成，共生成 {} 条", greetings.size());
        } catch (Exception e) {
            // 单次刷新失败不影响下次执行，下一次到点会自动重试
            log.error("问候语定时刷新失败: {}", e.getMessage(), e);
        }
    }
}
