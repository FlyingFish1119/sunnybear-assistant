package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage
 *
 * @Project LittleBear
 * @Author FlyingFish-SunnyBear
 * @Date 2025/12/26 13:33
 */

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface ToolKitComponent {
    Class<? extends ToolKit>[] value();
}
