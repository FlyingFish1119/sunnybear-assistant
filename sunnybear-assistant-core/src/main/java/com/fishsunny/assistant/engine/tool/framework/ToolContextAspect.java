package com.fishsunny.assistant.engine.tool.framework;


import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ToolContextAspect
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/4 16:52
 */
@Aspect
@Component
public class ToolContextAspect {

    @Around("@annotation(toolIncludeContext)")
    public Object checkToolContext(ProceedingJoinPoint joinPoint,
                                   ToolIncludeContext toolIncludeContext) throws Throwable {
        // 1. 拿到方法实参里那个 Map<String, Object>
        Map<String, Object> context = findContextMap(joinPoint);

        // 2. 方法里压根没有 Map，或声明的 key 缺失 → 直接拦下
        String[] keys = toolIncludeContext.key();
        Class<?>[] types = toolIncludeContext.type();

        if (keys.length > 0 && context == null) {
            throw new ToolExecutor.ToolExecuteException(
                    "方法缺少 context 参数: " + joinPoint.getSignature().toShortString());
        }

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Object value = context.get(key);
            if (value == null) {
                throw new ToolExecutor.ToolExecuteException("上下文缺少必需的 key: " + key);
            }
            // type 与 key 按索引对应，type 可以比 key 短（只校验前 N 个）
            if (i < types.length && types[i] != null && !types[i].isInstance(value)) {
                throw new ToolExecutor.ToolExecuteException(
                        "上下文 key=" + key + " 期望 " + types[i].getSimpleName()
                                + "，实际 " + value.getClass().getSimpleName());
            }
        }

        // 3. 校验通过，放行
        return joinPoint.proceed();
    }

    /**
     * 按参数类型精确找 Map 参数（比 instanceof 更稳，不会误抓别的 Map 参数）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findContextMap(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] paramTypes = signature.getMethod().getParameterTypes();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramTypes.length; i++) {
            if (Map.class.isAssignableFrom(paramTypes[i])) {
                return (Map<String, Object>) args[i];
            }
        }
        return null;
    }
}