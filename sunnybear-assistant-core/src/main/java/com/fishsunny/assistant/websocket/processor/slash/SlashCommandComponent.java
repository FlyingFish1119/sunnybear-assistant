package com.fishsunny.assistant.websocket.processor.slash;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/20 18:15
 */

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Scope("prototype")
public @interface SlashCommandComponent {
}
