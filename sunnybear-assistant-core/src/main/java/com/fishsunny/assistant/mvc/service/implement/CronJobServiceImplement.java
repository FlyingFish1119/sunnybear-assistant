package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 定时任务服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.cron.CronScheduler;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.mvc.dao.CronJobRepository;
import com.fishsunny.assistant.mvc.service.CronJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CronJobServiceImplement implements CronJobService {

    private static final Logger log = LoggerFactory.getLogger(CronJobServiceImplement.class);

    private final CronJobRepository cronJobRepository;
    private final CronScheduler cronScheduler;

    public CronJobServiceImplement(CronJobRepository cronJobRepository, CronScheduler cronScheduler) {
        this.cronJobRepository = cronJobRepository;
        this.cronScheduler = cronScheduler;
    }

    @Override
    public CronJob create(String title, String description, String cron, String message, boolean enablePro) {
        validateCron(cron);
        CronJob cronJob = new CronJob()
                .setTitle(title)
                .setDescription(description)
                .setCron(cron)
                .setMessage(message)
                .setEnablePro(enablePro);
        CronJob saved = cronJobRepository.insert(cronJob);
        log.info("创建定时任务: id={}, title={}, cron={}, enablePro={}", saved.getId(), saved.getTitle(), saved.getCron(), saved.getEnablePro());
        cronScheduler.scheduleJob(saved);
        return saved;
    }

    @Override
    public CronJob update(Integer id, String title, String description, String cron, String message, boolean enablePro) {
        validateCron(cron);
        CronJob existing = cronJobRepository.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("定时任务不存在: id=" + id);
        }
        existing.setTitle(title);
        existing.setDescription(description);
        existing.setCron(cron);
        existing.setMessage(message);
        existing.setEnablePro(enablePro);
        CronJob saved = cronJobRepository.update(existing);
        log.info("更新定时任务: id={}", saved.getId());
        cronScheduler.scheduleJob(saved);
        return saved;
    }

    @Override
    public CronJob delete(Integer id) {
        CronJob deleted = cronJobRepository.deleteById(id);
        if (deleted != null) {
            log.info("删除定时任务: id={}", deleted.getId());
            cronScheduler.cancelJob(deleted.getId());
        } else {
            log.warn("删除定时任务失败，记录不存在: id={}", id);
        }
        return deleted;
    }

    @Override
    public CronJob findById(Integer id) {
        return cronJobRepository.selectById(id);
    }

    @Override
    public List<CronJob> listAll() {
        return cronJobRepository.selectAll();
    }

    /** 校验 cron 表达式（Spring 6 字段格式：秒 分 时 日 月 周） */
    private void validateCron(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (Exception e) {
            throw new IllegalArgumentException("cron 表达式格式无效: " + cron + "。请使用 6 字段格式：秒 分 时 日 月 周（如 '0 */5 * * * *' 表示每 5 分钟）");
        }
    }
}
