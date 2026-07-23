package com.fishsunny.assistant.plug.qq.config;

/*
 * @Usage QQ Bot 配置属性
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:43
 */

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "plug.qq.bot")
public class QQBotOption {

    /** 允许自动回复的 QQ 号列表 */
    private List<Long> replayIds = new ArrayList<>();

    private String apiUrl = "http://127.0.0.1:3000";
}
