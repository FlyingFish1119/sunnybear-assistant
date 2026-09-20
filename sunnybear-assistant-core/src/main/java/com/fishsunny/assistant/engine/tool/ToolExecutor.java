package com.fishsunny.assistant.engine.tool;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 01:08
 */

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.cancel.ChatCancelContext;
import com.fishsunny.assistant.engine.cancel.ChatCancelToken;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.utils.SessionFileManager;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Component
public class ToolExecutor {

    /**
     * 取消后等待工具收尾的窗口：响应中断的工具会在这段时间内退出；
     * 不响应中断的（native 调用、死循环等）到点即放弃等待，用占位响应收场，避免「停止」被拖成卡死。
     */
    private static final long CANCEL_DRAIN_TIMEOUT_MILLIS = 3000;

    Map<String, ToolHandler> toolMap = new HashMap<>();
    Map<Class<? extends ToolKit>, ToolKit> toolKitMap = new HashMap<>();

    private final List<ToolResponseHandleChain> toolResponseHandleChains = new ArrayList<>();

    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    private final SessionFileManager sessionFileManager;

    public ToolExecutor(List<ToolKit> toolKits,
                        List<ToolResponseHandleChain> toolResponseHandleChains,
                        ObjectMapper objectMapper,
                        SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.sessionFileManager = sessionFileManager;
        for (ToolKit toolKit : toolKits) {
            toolKitMap.put(toolKit.getClass(), toolKit);
            for (ToolHandler tool : toolKit.getTools()) {
                toolMap.put(tool.name(), tool);
            }
        }
        this.toolResponseHandleChains.addAll(toolResponseHandleChains.stream().sorted(Comparator.comparingInt(ToolResponseHandleChain::getOrder)).toList());
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    /** 工具执行生命周期回调：beforeExec 调度前触发，afterExec 每次工具执行完成后「恰好一次」 */
    public record ToolProvider(Consumer<ToolRequest> beforeExec, Consumer<ToolExecuteResponse> afterExec) {}

    /**
     * 后置处理链回调 —— 与 {@link ToolProvider#afterExec()} 解耦，避免复用 afterExec 导致其被重复调用。
     * 每批工具执行完、链处理结束后回调一次，仅携带被链改写过的响应（未改写则不回调）。
     */
    @FunctionalInterface
    public interface ToolResponseHandleProvider {
        void afterHandle(List<ToolExecuteResponse> changedResponses);
    }


    /** 按名字取本机工具处理器，不存在返回 null。只读查询用，不走任何过滤/覆盖 */
    public ToolHandler getTool(String toolName) {
        return toolMap.get(toolName);
    }

    public List<ToolExecuteResponse> executeAdapter(List<AIAdapter.ToolCall> toolCalls, Map<String, Object> context) {
        List<ToolRequest> requests = ToolRequest.convert(toolCalls);
        return execute(requests, context);
    }

    public List<ToolExecuteResponse> executeAdapter(List<AIAdapter.ToolCall> toolCalls, Map<String, Object> context, ToolProvider provider) {
        return executeAdapter(toolCalls, context, provider, null);
    }

    public List<ToolExecuteResponse> executeAdapter(List<AIAdapter.ToolCall> toolCalls,
                                                    Map<String, Object> context,
                                                    ToolProvider provider,
                                                    ToolResponseHandleProvider responseHandleProvider) {
        List<ToolRequest> requests = ToolRequest.convert(toolCalls);
        return execute(requests, context, provider, responseHandleProvider);
    }

    public List<ToolExecuteResponse> execute(List<ToolRequest> requests, Map<String, Object> context) {
        return execute(requests, context, new ToolProvider(null, null), null);
    }

    // ======================== 异步版本（已备注，使用线程池+CompletableFuture） ========================
    public List<ToolExecuteResponse> execute(List<ToolRequest> requests, Map<String, Object> context, ToolProvider provider) {
        return execute(requests, context, provider, null);
    }

    public List<ToolExecuteResponse> execute(List<ToolRequest> requests,
                                             Map<String, Object> context,
                                             ToolProvider provider,
                                             ToolResponseHandleProvider responseHandleProvider) {
        if (requests == null || requests.isEmpty()) {
            return new ArrayList<>();
        }
        ToolProvider safeProvider = provider == null ? new ToolProvider(null, null) : provider;

        // 捕获本轮取消令牌并绑到每个工具任务线程上：工具内部再发起的 translate 会自动继承它，
        // 「停止」因此覆盖整条链路（含子请求），而不只是收流那一段
        ChatCancelToken token = ChatCancelContext.current();
        List<CompletableFuture<ToolExecuteResponse>> futures = new ArrayList<>(requests.size());
        for (ToolRequest request : requests) {
            // 已中止：不再推「开始执行」通知。任务仍照常提交，因为占位响应必须产出并触发 afterExec 契约
            if (token == null || !token.isCancelled()) {
                if (safeProvider.beforeExec() != null) {
                    safeProvider.beforeExec().accept(request);
                }
            }
            CompletableFuture<ToolExecuteResponse> future = CompletableFuture.supplyAsync(() ->
                    runWithCancelContext(token, request, context, safeProvider.afterExec()), executorService);
            futures.add(future);
        }

        // 先全部 join 收集，保证后置处理链看到的是本批次最终状态（如 mark 工具可能同时修改了会话清单），
        // 避免边 join 边处理时读到中间态
        List<ToolExecuteResponse> responses = new ArrayList<>(requests.size());
        for (int i = 0; i < futures.size(); i++) {
            ToolRequest request = requests.get(i);
            try {
                responses.add(awaitResult(futures.get(i), request, token));
            } catch (Exception e) {
                responses.add(new ToolExecuteResponse(request.getToolName(),
                        "工具[" + request.getToolName() + "]执行异常，原因是：" + e.getMessage()).setSucceed(false));
            }
        }
        if (!toolResponseHandleChains.isEmpty()) {
            applyResponseHandleChains(responses, context, responseHandleProvider);
        }
        return responses;
    }

    /**
     * 依次执行后置处理链；任一链异常只影响自身。
     * 链执行完通过独立的 {@link ToolResponseHandleProvider#afterHandle} 回调一次被改写的响应，
     * 与 execute 生命周期回调 afterExec 解耦，保证 afterExec 始终「恰好一次」。
     */
    private void applyResponseHandleChains(List<ToolExecuteResponse> responses,
                                           Map<String, Object> context,
                                           ToolResponseHandleProvider responseHandleProvider) {
        Map<ToolExecuteResponse, String> originResults = new IdentityHashMap<>();
        for (ToolExecuteResponse response : responses) {
            originResults.put(response, response.getResult());
        }
        Map<String, Object> safeContext = context == null ? new HashMap<>() : context;
        for (ToolResponseHandleChain chain : toolResponseHandleChains) {
            try {
                chain.handle(responses, safeContext);
            } catch (Exception e) {
                String errorMessage = "处理链" + chain.getClass().getSimpleName() + "处理失败，原因是：" + e.getMessage();
                ToolExecuteResponse last = responses.isEmpty() ? null : responses.getLast();
                if (last != null) {
                    last.setResult(last.getResult() + "\n\n" + errorMessage);
                }
                log.warn(errorMessage);
            }
        }
        if (responseHandleProvider == null) {
            return;
        }
        List<ToolExecuteResponse> changed = new ArrayList<>();
        for (ToolExecuteResponse response : responses) {
            if (!Objects.equals(originResults.get(response), response.getResult())) {
                changed.add(response);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        try {
            responseHandleProvider.afterHandle(changed);
        } catch (Exception e) {
            log.warn("后置处理链回调失败: {}", e.getMessage());
        }
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
                // 有超时限制：工具逻辑丢到独立任务上跑，本线程只负责限时等待
                AtomicReference<Thread> workerRef = new AtomicReference<>();
                CompletableFuture<ToolExecuteResponse> future = CompletableFuture.supplyAsync(() -> {
                    workerRef.set(Thread.currentThread());
                    return executeNow(handler, toolName, arguments, context);
                }, executorService);
                try {
                    response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    workerRef.get().interrupt();
                    log.warn("工具[{}]执行超时（{}ms），已中断执行线程", toolName, timeoutMs);
                    response = new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行超时（" + timeoutMs + "ms），已强制中断").setSucceed(false);
                } catch (InterruptedException e) {
                    // 用户中止打断了等待：连执行线程一起中断，不让它在后台继续跑。
                    // 不恢复中断标志 —— 本线程随后还要推工具结果通知，恢复会让这些阻塞调用立刻抛异常
                    workerRef.get().interrupt();
                    response = new ToolExecuteResponse(toolName, "工具[" + toolName + "]已被用户中止").setSucceed(false);
                } catch (Exception e) {
                    response = new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行异常，原因是：" + e.getMessage()).setSucceed(false);
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
            ToolExecuteResponse response = handler.action(safeArguments, context).setSucceed(true);
            // 多模态结果落盘：工具实现了 MultimodalResultAble 时，由工具把 base64 写入会话文件目录，
            // 并把内容里的文件名回写为可移植引用（形如 "sessionId:fileName"）。
            if (handler instanceof MultimodalResultAble multimodalHandler) {
                try {
                    ChatSession chatSession = (ChatSession) context.get("chatSession");
                    String sessionId = chatSession == null ? null : chatSession.getId();
                    multimodalHandler.writeFile(response.getMultimodalContents(), sessionFileManager, sessionId);
                } catch (Exception e) {
                    throw new ToolExecuteException("工具[" + toolName + "]执行成功，但文件结果落盘失败，请确认执行状态避免冲突: " + e.getMessage());
                }
            }
            return response;
        } catch (ToolExecuteException e) {
            // 中止导致的原因常常是 null（InterruptedException 不带 message，被工具原样透传上来），
            // 识别中断状态给出可读文案，否则前端会显示「执行失败，原因是：null」
            if (Thread.currentThread().isInterrupted()) {
                return new ToolExecuteResponse(toolName, "工具[" + toolName + "]已被用户中止").setSucceed(false);
            }
            String reason = StringUtils.hasText(e.getMessage()) ? e.getMessage() : "未知原因";
            return new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行失败，原因是：" + reason).setSucceed(false);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return new ToolExecuteResponse(toolName, "工具[" + toolName + "]已被用户中止").setSucceed(false);
            }
            return new ToolExecuteResponse(toolName, "工具[" + toolName + "]执行异常，原因是：" + e.getMessage()).setSucceed(false);
        }
    }

    // ======================== 取消支持 ========================

    /**
     * 在工具任务线程内继承父令牌并登记线程：工具内部再发起的 translate 因此自动落在同一轮取消范围内，
     * 取消时本线程会被 interrupt，让响应中断的工具（子进程、MCP、阻塞 IO）立刻退出。
     */
    private ToolExecuteResponse runWithCancelContext(ChatCancelToken token, ToolRequest request,
                                                     Map<String, Object> context,
                                                     Consumer<ToolExecuteResponse> afterExec) {
        if (token == null) {
            return doExecute(request, context, afterExec);
        }
        ChatCancelContext.bind(token);
        token.registerThread(Thread.currentThread());
        try {
            // 已中止：不真正执行工具，直接给占位响应。afterExec 仍要触发，保持「每次执行恰好一次」的契约
            if (token.isCancelled()) {
                ToolExecuteResponse response = cancelledResponse(request);
                if (afterExec != null) {
                    afterExec.accept(response);
                }
                return response;
            }
            return doExecute(request, context, afterExec);
        } finally {
            token.unregisterThread(Thread.currentThread());
            ChatCancelContext.unbind();
        }
    }

    /**
     * 等待单个工具结果。未取消时保持原有的无限等待语义；
     * 已取消时只给一个收尾窗口，超时就放弃等待 —— 工具不响应中断（native 调用、死循环）时
     * 「停止」不能被它拖住，但仍要产出响应，保证 assistant 的 tool_call 有回话可落库。
     */
    private ToolExecuteResponse awaitResult(CompletableFuture<ToolExecuteResponse> future,
                                            ToolRequest request, ChatCancelToken token) throws Exception {
        if (token == null) {
            return future.join();
        }
        try {
            return future.get(CANCEL_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            if (token.isCancelled()) {
                log.warn("工具[{}]未在 {}ms 内响应中止，已放弃等待", request.getToolName(), CANCEL_DRAIN_TIMEOUT_MILLIS);
                return cancelledResponse(request);
            }
            return future.join();
        }
    }

    /**
     * 取消占位响应：不保留工具输出，但必须存在 —— assistant 的 tool_call 没有对应回话时，
     * 下一轮把历史发给模型会被直接拒绝，整个会话都发不出消息。
     */
    private static ToolExecuteResponse cancelledResponse(ToolRequest request) {
        return new ToolExecuteResponse(request.getToolName(), "用户已中止本次操作，工具未执行完成。")
                .setSucceed(false)
                .setToolCallId(request.getToolCallId());
    }

    /** 同上，供工具调用循环在「还没提交执行就已中止」时批量补占位响应 */
    public static List<ToolExecuteResponse> cancelledResponses(List<AIAdapter.ToolCall> toolCalls) {
        List<ToolExecuteResponse> responses = new ArrayList<>(toolCalls.size());
        for (AIAdapter.ToolCall toolCall : toolCalls) {
            responses.add(new ToolExecuteResponse(toolCall.getFunction().getName(), "用户已中止本次操作，工具未执行。")
                    .setSucceed(false)
                    .setToolCallId(toolCall.getId()));
        }
        return responses;
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
        if (CollectionUtils.isEmpty(includeKits)) {
            return tools;
        }
        for (Map.Entry<Class<? extends ToolKit>, ToolKit> entry : toolKitMap.entrySet()) {
            if (! includeKits.contains(entry.getKey())) {
                continue;
            }
            for (ToolHandler tool : entry.getValue().getTools()) {
                tools.add(function.apply(tool.getRegister()));
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
        return buildToolExcluding(function, List.of(), excludeHandlers);
    }

    /**
     * 构建所有工具注册信息，同时按 ToolKit 类型与 Handler 名称排除。
     * <p>
     * 先按 kit 排除整个工具集，再按名字剔除个别工具（如只能经 agent_tool 路由的子 Agent 本体）。
     * 两个入参都为空时不排除任何工具、返回全量 —— 与 {@link #buildToolByHandlers} 的 include 语义相反。
     *
     * @param function        转换函数，将 ToolRegister 转为目标类型
     * @param excludeKits     需要排除的 ToolKit 类型列表，为 null 或空时不按 kit 排除
     * @param excludeHandlers 需要排除的 Handler 名称集合，为 null 或空时不按名字排除
     * @param <T>             目标类型
     * @return 转换后的工具注册列表
     */
    public <T> List<T> buildToolExcluding(Function<ToolRegister, T> function,
                                          List<Class<? extends ToolKit>> excludeKits,
                                          Set<String> excludeHandlers) {
        List<T> tools = new ArrayList<>();
        boolean noExcludeKits = CollectionUtils.isEmpty(excludeKits);
        boolean noExcludeHandlers = CollectionUtils.isEmpty(excludeHandlers);
        // 按名字去重：@ToolKitComponent 的值是数组，一个工具可以同时挂在多个 kit 上，
        // 按 kit 遍历会把它广告多份。当前没有这种工具，但插件以后可能有。
        Set<String> added = new HashSet<>();
        for (Map.Entry<Class<? extends ToolKit>, ToolKit> entry : toolKitMap.entrySet()) {
            if (!noExcludeKits && excludeKits.contains(entry.getKey())) {
                continue;
            }
            for (ToolHandler tool : entry.getValue().getTools()) {
                if (!noExcludeHandlers && excludeHandlers.contains(tool.name())) {
                    continue;
                }
                if (added.add(tool.name())) {
                    tools.add(function.apply(tool.getRegister()));
                }
            }
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
    public <T> List<T> buildToolByHandlers(Function<ToolRegister, T> function, Set<String> includeHandlers) {
        List<T> tools = new ArrayList<>();
        if (CollectionUtils.isEmpty(includeHandlers)) {
            return tools;
        }
        for (ToolHandler tool : toolMap.values()) {
            if (!includeHandlers.contains(tool.name())) {
                continue;
            }
            tools.add(function.apply(tool.getRegister()));
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
        @JsonIgnore
        private List<MultimodalContent> multimodalContents = new ArrayList<>();
        public void setMultimodalContents(List<MultimodalContent> multimodalContents) {
            this.multimodalContents = multimodalContents == null ? new ArrayList<>() : multimodalContents;
        }

        public ToolExecuteResponse(String name, String result) {
            this.name = name;
            this.result = result;
        }

        public ToolExecuteResponse modalContent(String path, String type, String data) {
            this.multimodalContents = this.multimodalContents == null ? new ArrayList<>() : this.multimodalContents;
            this.multimodalContents.add(new MultimodalContent(path, type, data));
            return this;
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

        public ToolRequest(String toolCallId, String toolName, String arguments) {
            this.toolCallId = toolCallId;
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
