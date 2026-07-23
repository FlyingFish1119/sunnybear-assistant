package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage AI 问候语接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;
import com.fishsunny.assistant.mvc.service.AiGreetingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/greeting")
public class GreetingController {

    private static final Logger log = LoggerFactory.getLogger(GreetingController.class);

    private final AiGreetingService aiGreetingService;

    @Autowired
    public GreetingController(AiGreetingService aiGreetingService) {
        this.aiGreetingService = aiGreetingService;
    }

    /**
     * 获取一条匹配当前时间段的问候语，没有则随机获取
     */
    @RequestMapping("/random")
    public RestResponse getRandom() {
        try {
            AiGreeting greeting = aiGreetingService.getCurrentGreeting();
            if (greeting == null) {
                return new RestResponse().success(null);
            }
            return new RestResponse().success(greeting);
        } catch (Exception e) {
            log.error("获取问候语失败: {}", e.getMessage(), e);
            return new RestResponse().error("获取问候语失败: " + e.getMessage());
        }
    }

    /**
     * 使用 mission AI 设置，为所有时间段各生成一条问候语并返回
     */
    @RequestMapping("/generate")
    public RestResponse generate() {
        try {
            List<AiGreeting> greetings = aiGreetingService.generateGreeting();
            return new RestResponse().success(greetings);
        } catch (Exception e) {
            log.error("生成问候语失败: {}", e.getMessage(), e);
            return new RestResponse().error("生成问候语失败: " + e.getMessage());
        }
    }
}
