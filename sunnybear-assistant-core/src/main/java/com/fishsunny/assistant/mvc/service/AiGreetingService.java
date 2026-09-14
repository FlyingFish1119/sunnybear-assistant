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
     * 使用 mission AI 设置，为每个时间段各生成 1 条问候语 + 4 条建议提问并存储。
     * 生成提示词中会附带核心记忆，使内容更贴合用户
     *
     * @return 生成结果列表（每时段 1 条，含 greeting 与 suggestions）
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
