package com.fishsunny.assistant.engine.adapter;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 07:59
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "engine.adapter-register")
public class AIAdapterProperties {

    private List<AIAdapterRegister> register = new ArrayList<>();
}

