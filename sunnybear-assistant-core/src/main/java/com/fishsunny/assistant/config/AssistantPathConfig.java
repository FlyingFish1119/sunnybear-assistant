package com.fishsunny.assistant.config;


import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ChatSessionConfig
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/11 15:29
 */
@Component
@Getter
public class AssistantPathConfig {

    @Value("${assistant.file.base-path:data/}")
    private String fileBasePath;

    @Value("${assistant.settings.base-path:settings/}")
    private String settingsBasePath;
}
