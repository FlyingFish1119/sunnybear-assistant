package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage AI 问候语数据访问层接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;

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
}
