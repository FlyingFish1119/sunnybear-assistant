package com.fishsunny.assistant.engine.tool;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 01:08
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    Map<String, ToolHandler> toolMap = new HashMap<>();
    Map<Class<? extends ToolKit>, ToolKit> toolKitMap = new HashMap<>();
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;

    public ToolExecutor(List<ToolKit> toolKits, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (ToolKit toolKit : toolKits) {
            toolKitMap.put(toolKit.getClass(), toolKit);
            for (ToolHandler tool : toolKit.getTools()) {
                toolMap.put(tool.name(), tool);
            }
        }
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    public record ToolProvider(Consumer<ToolRequest> beforeExec, Consumer<ToolExecuteResponse> afterExec) {}

    public List<ToolExecuteResponse> execute(List<ToolRequest> requests, Map<String, Object> context) {
        return execute(requests, context, new ToolProvider(null, null));
    }

    // ======================== 异步版本（已备注，使用线程池+CompletableFuture） ========================
    public List<ToolExecuteResponse> execute(List<ToolRequest> requests, Map<String, Object> context, ToolProvider provider) {
        if (requests == null || requests.isEmpty()) {
            return new ArrayList<>();
        }
        ToolProvider safeProvider = provider == null ? new ToolProvider(null, null) : provider;
        List<CompletableFuture<ToolExecuteResponse>> futures = new ArrayList<>(requests.size());
        for (ToolRequest request : requests) {
            if (safeProvider.beforeExec() != null) {
                safeProvider.beforeExec().accept(request);
            }
            CompletableFuture<ToolExecuteResponse> future = CompletableFuture.supplyAsync(() ->
                    doExecute(request, context, safeProvider.afterExec()), executorService);
            futures.add(future);
        }
        List<ToolExecuteResponse> responses = new ArrayList<>(requests.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                responses.add(futures.get(i).join());
            } catch (Exception e) {
                ToolRequest request = requests.get(i);
                responses.add(new ToolExecuteResponse(request.getToolName(),
                        "工具[" + request.getToolName() + "]执行异常，原因是：" + e.getMessage()).setSucceed(false));
            }
        }
        return responses;
    }

    private ToolExecuteResponse doExecute(ToolRequest toolRequest, Map<String, Object> context, Consumer<ToolExecuteResponse> afterExec) {
        String toolName = toolRequest.getToolName();
        String arguments = toolRequest.getArguments();

        ToolExecuteResponse response;
        ToolHandler handler = toolMap.get(toolName);
        if (handler == null) {
            response = new ToolExecuteResponse(toolName, "工具[" + toolName + "]不存在").setSucceed(false);
        } else {
            Integer timeoutMs = handler.getRegister().getTimeoutMs();
            if (timeoutMs == null) {
                // 无超时限制，直接同步执行
                response = executeNow(handler, toolName, arguments, context);
            } else {
                // 有超时限制，通过 CompletableFuture 做硬超时
                CompletableFuture<ToolExecuteResponse> future = CompletableFuture.supplyAsync(
                        () -> executeNow(handler, toolName, arguments, context), executorService);
                try {
                    response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    log.warn("工具[{}]执行超时（{}ms），已强制中断", toolName, timeoutMs);
                    response = new ToolExecuteResponse(toolName,
                            "工具[" + toolName + "]执行超时（" + timeoutMs + "ms），已强制中断").setSucceed(false);
                } catch (Exception e) {
                    response = new ToolExecuteResponse(toolName,
                            "工具[" + toolName + "]执行异常，原因是：" + e.getMessage()).setSucceed(false);
                }
            }
        }
        response.setToolCallId(toolRequest.getToolCallId());
        // afterExec 在所有完成路径都触发（成功/失败/超时/工具不存在/无超时），保证 hook 不遗漏
        if (afterExec != null) {
            afterExec.accept(response);
        }
        return response;
    }

    /** 实际执行工具调用，抽取为独立方法供超时包装复用 */
    private ToolExecuteResponse executeNow(ToolHandler handler, String toolName, String arguments, Map<String, Object> context) {
        try {
            String safeArguments = repairJson(arguments);
            return handler.action(safeArguments, context).setSucceed(true);
        } catch (ToolExecuteException e) {
            return new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行失败，原因是：" + e.getMessage()).setSucceed(false);
        } catch (Exception e) {
            return new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行异常，原因是：" + e.getMessage()).setSucceed(false);
        }
    }

    // ======================== JSON 修复 ========================

    /**
     * 修复 AI 模型生成的 JSON 参数中常见的转义问题。
     * <p>
     * 核心规则：在 JSON 字符串值内部，如果出现了未转义的双引号 {@code "}（不是字符串的结束引号），
     * 则需要转义为 {@code \"}。判断方法是：当遇到一个 {@code "} 且当前在字符串内，
     * 检查其后第一个非空白字符 —— 如果是 {@code , : } ]} 等 JSON 结构字符，说明这是合法的字符串结束；
     * 否则说明是字符串内容中的双引号，需要转义。
     * </p>
     *
     * @param json 原始 JSON 字符串，可能包含未转义的双引号
     * @return 修复后的 JSON 字符串
     */
    private String repairJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        // 先用 Jackson 快速校验 —— 如果本身就是合法 JSON，直接返回，不浪费时间
        try {
            objectMapper.readTree(json);
            return json;
        } catch (Exception ignored) {
            // JSON 非法，尝试修复
        }

        StringBuilder sb = new StringBuilder(json.length() + 64);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                continue;
            }

            if (c == '"') {
                if (!inString) {
                    // 进入字符串
                    inString = true;
                    sb.append(c);
                } else {
                    // 遇到引号，需要判断是字符串结束还是内容中的引号
                    int peek = i + 1;
                    while (peek < json.length() && Character.isWhitespace(json.charAt(peek))) {
                        peek++;
                    }
                    if (peek >= json.length()
                            || json.charAt(peek) == ','
                            || json.charAt(peek) == ':'
                            || json.charAt(peek) == '}'
                            || json.charAt(peek) == ']') {
                        // 合法结束：后面跟的是 JSON 结构字符
                        inString = false;
                        sb.append(c);
                    } else {
                        // 字符串内容中的未转义双引号 → 转义它
                        sb.append("\\\"");
                    }
                }
                continue;
            }

            sb.append(c);
        }

        String repaired = sb.toString();
        if (!repaired.equals(json)) {
            log.warn("JSON 参数中存在未转义的双引号，已自动修复");
        }
        return repaired;
    }

    // ======================== Tool Builder ========================

    public <T> List<T> buildTool(Function<ToolRegister, T> function) {
        List<T> tools = new ArrayList<>();
        for (ToolHandler tool : toolMap.values()) {
            tools.add(function.apply(tool.getRegister()));
        }
        return tools;
    }

    /**
     * 根据指定的 ToolKit 类型过滤并构建工具注册信息
     *
     * @param function    转换函数，将 ToolRegister 转为目标类型
     * @param includeKits 需要包含的 ToolKit 类型列表，为 null 或空时返回空列表
     * @param <T>         目标类型
     * @return 转换后的工具注册列表
     */
    public <T> List<T> buildTool(Function<ToolRegister, T> function, List<Class<? extends ToolKit>> includeKits) {
        List<T> tools = new ArrayList<>();
        if (includeKits == null || includeKits.isEmpty()) {
            return tools;
        }
        for (Map.Entry<Class<? extends ToolKit>, ToolKit> entry : toolKitMap.entrySet()) {
            if (includeKits.contains(entry.getKey())) {
                for (ToolHandler tool : entry.getValue().getTools()) {
                    tools.add(function.apply(tool.getRegister()));
                }
            }
        }
        return tools;
    }

    /**
     * 构建所有工具注册信息，排除指定的 Handler 名称。
     *
     * @param function        转换函数，将 ToolRegister 转为目标类型
     * @param excludeHandlers 需要排除的 Handler 名称集合，为 null 或空时不排除任何工具
     * @param <T>             目标类型
     * @return 转换后的工具注册列表
     */
    public <T> List<T> buildToolExcluding(Function<ToolRegister, T> function, Set<String> excludeHandlers) {
        List<T> tools = new ArrayList<>();
        for (ToolHandler tool : toolMap.values()) {
            if (excludeHandlers != null && excludeHandlers.contains(tool.name())) {
                continue;
            }
            tools.add(function.apply(tool.getRegister()));
        }
        return tools;
    }

    /**
     * 根据指定的 Handler 名称过滤并构建工具注册信息
     *
     * @param function      转换函数，将 ToolRegister 转为目标类型
     * @param includeHandlers 需要包含的 Handler 名称集合，为 null 或空时返回空列表
     * @param <T>           目标类型
     * @return 转换后的工具注册列表
     */
    public <T> List<T> buildToolByHandlers(Function<ToolRegister, T> function, java.util.Set<String> includeHandlers) {
        List<T> tools = new ArrayList<>();
        if (includeHandlers == null || includeHandlers.isEmpty()) {
            return tools;
        }
        for (ToolHandler tool : toolMap.values()) {
            if (includeHandlers.contains(tool.name())) {
                tools.add(function.apply(tool.getRegister()));
            }
        }
        return tools;
    }

    @Data
    @Accessors(chain = true)
    public static class ToolExecuteResponse {
        private String toolCallId;
        private String name;
        private boolean succeed;
        private String result;

        public ToolExecuteResponse(String name, String result) {
            this.name = name;
            this.result = result;
        }

        public void status(String toolCallId, boolean succeed) {
            this.toolCallId = toolCallId;
            this.succeed = succeed;
        }
    }

    @Getter
    @Accessors(chain = true)
    public static class ToolExecuteException extends Exception {
        private final String message;

        public ToolExecuteException(String message) {
            super(message);
            this.message = message;
        }
    }

    @Data
    @Accessors(chain = true)
    public static class ToolRequest {
        private String toolCallId;
        private String toolName;
        private String arguments;

        public ToolRequest() {
        }

        public ToolRequest(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        public static List<ToolRequest> convert(List<AIAdapter.ToolCall> toolCalls) {
            return toolCalls.stream().map(toolCall -> {
                ToolRequest toolRequest = new ToolRequest();
                toolRequest.setToolCallId(toolCall.getId());
                toolRequest.setToolName(toolCall.getFunction().getName());
                toolRequest.setArguments(toolCall.getFunction().getArguments());
                return toolRequest;
            }).toList();
        }
    }
}
