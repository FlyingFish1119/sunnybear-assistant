package com.fishsunny.assistant.remote;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteRepositoryRegistry {

    private final Map<String, Object> repositoryInstanceMap = new ConcurrentHashMap<>();

    /**
     * 登记一个可远程调用的仓储实例。key 用接口简名，与客户端 {@code repositoryInterface.getSimpleName()} 对齐。
     * 实例由调用方（BeanPostProcessor）直接传入，避免在建 bean 途中反查容器造成循环依赖。
     */
    public void register(Class<?> repositoryInterface, Object repositoryInstance) {
        repositoryInstanceMap.put(repositoryInterface.getSimpleName(), repositoryInstance);
    }

    public Object getRepositoryInstance(String repositoryClassName) {
        return repositoryInstanceMap.get(repositoryClassName);
    }
}
