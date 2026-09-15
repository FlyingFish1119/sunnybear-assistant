package com.fishsunny.assistant.remote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15 19:44
 */

@Data
@Component
@ConfigurationProperties(prefix = "assistant.remote-storage")
public class RemoteRepositoryProperties {

    private String remoteUrl = "ws://127.0.0.1:11451/ws/repo";

    private Long timeoutMs = 15000L;

    private String username = "username";

    private String password = "password";
}
