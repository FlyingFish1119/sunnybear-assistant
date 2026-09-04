package com.fishsunny.assistant.engine.tool.framework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

/**
 * ToolIncludeContext
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/4 16:53
 */

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolIncludeContext {
    String[] key() default {};
    Class<?>[] type() default {};
}
