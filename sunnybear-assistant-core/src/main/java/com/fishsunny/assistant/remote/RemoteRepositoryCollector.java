package com.fishsunny.assistant.remote;


import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * RemoteRepositroyCollector
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/15 17:50
 */

@Slf4j
@Component
public class RemoteRepositoryCollector implements BeanPostProcessor {

    private final RemoteRepositoryRegistry registry;

    public RemoteRepositoryCollector(RemoteRepositoryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        // @RemoteRepository 打在 Repository 接口上（TYPE 级），实现类本身取不到，必须沿接口找
        registerIfPresent(targetClass, bean);
        for (Class<?> repositoryInterface : ClassUtils.getAllInterfacesForClass(targetClass)) {
            registerIfPresent(repositoryInterface, bean);
        }
        return bean;
    }

    private void registerIfPresent(Class<?> type, Object bean) {
        RemoteRepository annotation = AnnotatedElementUtils.findMergedAnnotation(type, RemoteRepository.class);
        if (annotation != null) {
            // 直接登记当前 bean，别用 applicationContext.getBean —— 此刻它自身还在创建中，会触发循环依赖
            registry.register(annotation.repoCls(), bean);
            log.info("收集 RemoteRepository: {}", annotation.repoCls().getName());
        }
    }
}