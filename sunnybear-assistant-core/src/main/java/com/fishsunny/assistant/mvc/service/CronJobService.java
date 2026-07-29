package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 定时任务服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;

import java.util.List;

public interface CronJobService {

    /**
     * 创建定时任务
     *
     * @param title       任务标题
     * @param description 任务描述
     * @param cron        cron 表达式
     * @param message     触发消息
     * @return 创建后的 CronJob
     */
    CronJob create(String title, String description, String cron, String message, boolean enablePro);

    /**
     * 更新定时任务
     *
     * @param id          任务 ID
     * @param title       新的标题
     * @param description 新的描述
     * @param cron        新的 cron 表达式
     * @param message     新的触发消息
     * @param enablePro   是否启用高级模型
     * @return 更新后的 CronJob
     */
    CronJob update(Integer id, String title, String description, String cron, String message, boolean enablePro);

    /**
     * 删除定时任务
     *
     * @param id 任务 ID
     * @return 被删除的 CronJob
     */
    CronJob delete(Integer id);

    /**
     * 根据 ID 查询
     */
    CronJob findById(Integer id);

    /**
     * 列出所有定时任务
     */
    List<CronJob> listAll();
}
