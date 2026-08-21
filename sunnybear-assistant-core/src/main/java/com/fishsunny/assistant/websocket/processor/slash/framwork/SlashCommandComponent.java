package com.fishsunny.assistant.websocket.processor.slash.framwork;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/20 23:55
 */

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Component
@Scope("prototype")
public @interface SlashCommandComponent {
    String value();
}
