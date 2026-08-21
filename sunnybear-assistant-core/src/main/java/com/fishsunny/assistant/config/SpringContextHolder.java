package com.fishsunny.assistant.config;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    // 按类型拿
    public static <T> T getBean(Class<T> clazz) {
        return context.getBean(clazz);
    }

    // 按名称拿
    public static Object getBean(String name) {
        return context.getBean(name);
    }
}