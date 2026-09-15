package com.fishsunny.assistant.remote;

import org.springframework.stereotype.Repository;

import java.lang.annotation.*;

/**
 * RemoteRepository
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/15 17:48
 */

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RemoteRepository {
    Class<?> repoCls ();
}
