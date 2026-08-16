package com.fishsunny.assistant.mvc.service;

/*
 * @Usage AI 问候语业务层接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;

import java.time.LocalDateTime;
import java.util.List;

public interface AiGreetingService {

    /**
     * 使用 mission AI 设置，为所有时间段各生成 3 条问候语并存储。
     * 生成提示词中会附带核心记忆，使问候语更贴合用户
     *
     * @return 生成的问候语列表（每时段 3 条：上午、中午、下午、晚上、深夜）
     */
    public List<AiGreeting> generateGreeting() throws Exception;

    /**
     * 获取一条匹配当前时间段的问候语，没有则随机返回
     *
     * @return 匹配当前时段的问候语，若表中无数据则返回 null
     */
    public AiGreeting getCurrentGreeting() throws Exception;

    /**
     * 删除创建时间早于指定时间的问候语
     *
     * @param cutoff 时间边界，早于该时间的问候语会被删除
     * @return 删除的条数
     */
    public int deleteBefore(LocalDateTime cutoff);
}
