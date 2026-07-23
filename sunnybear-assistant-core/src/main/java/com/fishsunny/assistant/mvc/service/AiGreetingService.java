package com.fishsunny.assistant.mvc.service;

/*
 * @Usage AI 问候语业务层接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;

import java.util.List;

public interface AiGreetingService {

    /**
     * 使用 mission AI 设置，为所有时间段各生成一条问候语并存储
     *
     * @return 生成的问候语列表（上午、中午、下午、晚上、深夜）
     */
    public List<AiGreeting> generateGreeting() throws Exception;

    /**
     * 获取一条匹配当前时间段的问候语，没有则随机返回
     *
     * @return 匹配当前时段的问候语，若表中无数据则返回 null
     */
    public AiGreeting getCurrentGreeting() throws Exception;
}
