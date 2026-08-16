package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage AI 问候语数据访问层接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;

import java.time.LocalDateTime;
import java.util.List;

public interface AiGreetingRepository {

    public AiGreeting insert(AiGreeting greeting);

    public AiGreeting selectById(String id);

    /**
     * 随机获取一条问候语
     */
    public AiGreeting selectRandom();

    /**
     * 根据时间段获取问候语
     */
    public AiGreeting selectByGreetingTime(String greetingTime);

    public List<AiGreeting> selectAll();

    public int deleteById(String id);

    /**
     * 删除创建时间早于指定时间的问候语（用于定时清理过期数据）
     *
     * @param cutoff 时间边界（含边界值之前的都会被删除）
     * @return 删除的条数
     */
    public int deleteBefore(LocalDateTime cutoff);
}
