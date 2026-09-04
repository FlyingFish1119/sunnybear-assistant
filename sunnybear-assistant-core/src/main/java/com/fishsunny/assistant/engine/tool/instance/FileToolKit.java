package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 05:57
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "engine.tool.file.enable", havingValue = "true", matchIfMissing = true)
public class FileToolKit extends ToolKit {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FileLock {
        boolean readOnly() default false;
    }

    public FileToolKit(List<ToolHandler> tools, @Value("${engine.tool.file.enable:true}") boolean enable, ObjectMapper objectMapper) {
        super(tools, enable);
        this.objectMapper = objectMapper;
    }

    /** 规范化路径 → 读写锁。条目随用过的路径常驻，量级为工作区文件数，内存开销可忽略 */
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /**
     * 环绕标注了 @FileLock 的方法：解析入参中的文件路径，按注解决定读锁/写锁
     */
    @Around("@annotation(fileLock)")
    public Object lock(ProceedingJoinPoint joinPoint, FileLock fileLock) throws Throwable {
        String path = extractPath(joinPoint.getArgs());
        if (!StringUtils.hasText(path)) {
            log.warn("@FileLock 未从参数解析到文件路径，未加锁直接放行: {}", joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        ReentrantReadWriteLock rwLock = locks.computeIfAbsent(toKey(path), k -> new ReentrantReadWriteLock());
        boolean readOnly = fileLock.readOnly();
        if (readOnly) {
            rwLock.readLock().lock();
        } else {
            rwLock.writeLock().lock();
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (readOnly) {
                rwLock.readLock().unlock();
            } else {
                rwLock.writeLock().unlock();
            }
        }
    }

    /** 从方法参数中定位 argumentsJson（String），解析出 path 字段 */
    private String extractPath(Object[] args) {
        for (Object arg : args) {
            if (! (arg instanceof String json && json.contains("path"))) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(arg.toString());
                String path = node.path("path").asText(null);
                if (path != null && !path.isBlank()) {
                    return path;
                }
            } catch (Exception ignored) {
                // 非法 JSON 交给工具自身的参数校验报错，切面不重复报
            }
        }
        return null;
    }

    /** 规范化绝对路径转 key；Windows 下忽略大小写（与 FilePathLock 的 key 口径一致） */
    private String toKey(String path) {
        String key = Paths.get(path).toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? key.toLowerCase(Locale.ROOT) : key;
    }
}
