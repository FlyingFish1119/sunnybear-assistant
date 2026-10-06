package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 等待流程工具 - 接受等待时间（毫秒），等待指定时间后返回，让AI拥有真正的等待概念。
 *        可选 toolChain：在等待窗口内的指定时间节点按顺序自动执行其它工具，
 *        让 AI 等待到某个节点后直接介入，而无需等下一次 ReAct（避免时间偏差）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.cancel.ChatCancelContext;
import com.fishsunny.assistant.engine.cancel.ChatCancelToken;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalContent;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.wait-flow.enable:true}")
public class WaitFlowTool implements ToolHandler {

    public static final String NAME = "wait_flow_tool";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ToolExecutor toolExecutor;
    private final SecurityService securityService;
    private final ObjectMapper objectMapper;

    public WaitFlowTool(@Lazy ToolExecutor toolExecutor,
                        SecurityService securityService,
                        ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.securityService = securityService;
        this.objectMapper = objectMapper;

        ToolRegister.Parameters millisecondsParam = new ToolRegister.Parameters()
                .setParameterName("milliseconds")
                .setType("integer")
                .setDescription("需要等待的时间，单位：毫秒。工具会阻塞等待这段时间后返回结果。");

        ToolRegister.Parameters toolChainParam = new ToolRegister.Parameters()
                .setParameterName("toolChain")
                .setType("array")
                .setDescription("""
                        可选。等待窗口内按时间节点触发的工具链（到点即触发，各节点互不等待、可能并发），\
                        用于在等待到某个节点后直接介入，无需等下一次 ReAct。\
                        每项含 tool（工具名）、at（相对开始等待的毫秒偏移，0=立即；\
                        省略则在等待结束时执行）与 arguments（传给该工具的参数对象）。\
                        提供 toolChain 时会先请求用户确认；会话已开启无审查时跳过确认。\
                        禁止调用 wait_flow_tool 自身。""")
                .setItems(ToolRegister.Parameters.object(
                        "工具链节点",
                        List.of(
                                new ToolRegister.Parameters("tool", "string", "要调用的工具名称，不能是 wait_flow_tool 自身。"),
                                new ToolRegister.Parameters("at", "integer", "相对开始等待的毫秒偏移，0 表示立即执行；省略则表示在等待结束时执行。需满足 0 <= at <= milliseconds。"),
                                new ToolRegister.Parameters("arguments", "object", "传给该工具的参数对象（可选），字段需符合目标工具的参数定义。")
                        ),
                        List.of("tool")));

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        等待指定毫秒数后返回，让 AI 拥有真正的等待/定时概念。\
                        可选传入 toolChain：在等待窗口内的指定时间节点自动触发其它工具（到点即触发、互不等待），\
                        等待到该节点后立即介入，而不是等下一次 ReAct 再执行（避免时间偏差）。\
                        提供 toolChain 时会先请求用户确认（会话已开启无审查时跳过），\
                        确认后在隔离的临时上下文中以「无审查」方式执行整条链，执行完毕即恢复。""")
                .setRequired(List.of("milliseconds"))
                .setParameters(List.of(millisecondsParam, toolChainParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        long milliseconds = arguments.getMilliseconds();
        if (milliseconds <= 0) {
            throw new ToolExecutor.ToolExecuteException("参数 milliseconds 必须大于 0");
        }

        List<ChainNode> chain = validateAndPrepareChain(arguments.getToolChain(), milliseconds);

        ChatSession chatSession = context == null ? null : (ChatSession) context.get("chatSession");

        // 有工具链：先请求用户确认（无审查 / 定时任务无审查由 SecurityService 统一裁决）
        if (!chain.isEmpty()) {
            ensureConfirmable(chatSession, context);
            askForChain(chain, milliseconds, context);
        }

        // 工具链在「浅拷贝的 chatSession（unreviewed=true）」上执行，隔离于真实会话与同批次并行工具
        Map<String, Object> chainContext = chain.isEmpty() ? context : buildChainContext(context, chatSession);

        long startMillis = System.currentTimeMillis();
        LocalDateTime startTime = LocalDateTime.now();

        List<NodeResult> nodeResults;
        try (ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(chain.size(), new WaitSchedulerThreadFactory())) {
            if (chain.isEmpty()) {
                sleepUntil(startMillis, milliseconds);
                nodeResults = Collections.emptyList();
            } else {
                // 到点并行调度：每个节点在自己的 at 触发，互不等待（前一个执行超时也不会推迟后一个）。
                // 主线程只负责补足总等待时长，最后统一收集结果。
                ChatCancelToken token = ChatCancelContext.current();
                List<CompletableFuture<NodeResult>> futures = new ArrayList<>(chain.size());
                for (ChainNode node : chain) {
                    CompletableFuture<NodeResult> future = new CompletableFuture<>();
                    futures.add(future);
                    long delay = Math.max(0L, startMillis + node.at() - System.currentTimeMillis());
                    scheduler.schedule(() -> runNodeOnScheduler(node, chainContext, token, future), delay, TimeUnit.MILLISECONDS);
                }
                // 补足到总等待时长（工具执行超时则不再补）
                sleepUntil(startMillis, milliseconds);
                nodeResults = collectResults(chain, futures);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("等待被中止");
        }

        return buildResponse(milliseconds, startMillis, startTime, nodeResults);
    }

    // ======================== 工具链校验与准备 ========================

    /**
     * 校验工具链并转成可执行节点列表。
     * <p>
     * 节点按 at 升序排序（稳定排序，同一时刻保持模型给出的先后），仅用于结果聚合顺序；
     * 实际执行是到点并行触发、互不等待。省略 at 视为在等待结束时执行。
     * 禁止调用 wait_flow_tool 自身，避免无限递归。
     */
    private List<ChainNode> validateAndPrepareChain(List<ChainNodeArg> raw, long totalMillis) throws ToolExecutor.ToolExecuteException {
        if (CollectionUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        List<ChainNode> nodes = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            ChainNodeArg arg = raw.get(i);
            String label = "toolChain 第 " + (i + 1) + " 项";
            if (arg == null || !StringUtils.hasText(arg.getTool())) {
                throw new ToolExecutor.ToolExecuteException(label + "缺少 tool（工具名称）");
            }
            String tool = arg.getTool().trim();
            if (NAME.equals(tool)) {
                throw new ToolExecutor.ToolExecuteException(label + "不允许调用 wait_flow_tool 自身（防止递归）");
            }
            ToolHandler handler = toolExecutor.getTool(tool);
            if (handler == null) {
                throw new ToolExecutor.ToolExecuteException(label + "指定的工具不存在：" + tool);
            }
            long at = arg.getAt() == null ? totalMillis : arg.getAt();
            if (at < 0) {
                throw new ToolExecutor.ToolExecuteException(label + "的 at 不能为负数");
            }
            if (at > totalMillis) {
                throw new ToolExecutor.ToolExecuteException(label + "的 at（" + at + "ms）超出等待时长（" + totalMillis + "ms）");
            }
            String argumentsJson;
            try {
                argumentsJson = arg.getArguments() == null ? "{}" : objectMapper.writeValueAsString(arg.getArguments());
            } catch (Exception e) {
                throw new ToolExecutor.ToolExecuteException(label + "参数序列化失败：" + e.getMessage());
            }
            nodes.add(new ChainNode(tool, at, argumentsJson));
        }
        nodes.sort(Comparator.comparingLong(ChainNode::at));
        return nodes;
    }

    /** 未开启无审查时，工具链确认需要一条可交互的会话通道 */
    private void ensureConfirmable(ChatSession chatSession, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        if (chatSession != null && Boolean.TRUE.equals(chatSession.getUnreviewed())) {
            return;
        }
        if (chatSession != null && ChatSession.TYPE_CRON.equals(chatSession.getType())) {
            // 交给 SecurityService 抛出统一的定时任务提示
            return;
        }
        Object session = context == null ? null : context.get("session");
        if (!(session instanceof WebSocketSession)) {
            throw new ToolExecutor.ToolExecuteException("当前上下文缺少可交互的会话通道，无法确认工具链。");
        }
    }

    /** 向用户发送工具链执行确认并阻塞等待应答 */
    private void askForChain(List<ChainNode> chain, long milliseconds, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        StringBuilder sb = new StringBuilder();
        sb.append("### 等待工具链执行请求\n\n")
                .append("AI 请求在等待 **").append(milliseconds).append("ms** 期间，按时间节点自动顺序执行以下工具链：\n\n")
                .append("| 时间节点 | 工具 |\n")
                .append("|------|------|\n");
        for (ChainNode node : chain) {
            sb.append("| ").append(node.at()).append("ms | `").append(node.tool()).append("` |\n");
        }
        sb.append("\n> ⚠️ 确认后，这些工具将在隔离的临时无审查环境中直接执行，请确认无误后再允许。");
        try {
            securityService.ask(NAME, sb.toString(), null, context);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("工具链确认失败：" + e.getMessage());
        }
    }

    // ======================== 隔离上下文与执行 ========================

    /**
     * 构造工具链专用上下文：复制原 context，并把 chatSession 换成 unreviewed=true 的浅拷贝。
     * <p>
     * 直接改原 chatSession 会波及同批次并行执行的其它工具（共享同一 context 与会话对象），
     * 故用浅拷贝隔离：仅链内工具看到「无审查」，链结束后连同拷贝一起丢弃。
     */
    private Map<String, Object> buildChainContext(Map<String, Object> context, ChatSession chatSession) {
        Map<String, Object> chainContext = context == null ? new HashMap<>() : new HashMap<>(context);
        if (chatSession != null) {
            chainContext.put("chatSession", cloneUnreviewed(chatSession));
        }
        return chainContext;
    }

    private static ChatSession cloneUnreviewed(ChatSession source) {
        ChatSession copy = new ChatSession()
                .setId(source.getId())
                .setName(source.getName())
                .setCreateTime(source.getCreateTime())
                .setUpdateTime(source.getUpdateTime())
                .setEnablePro(source.getEnablePro())
                .setUnreviewed(true)
                .setCronId(source.getCronId());
        if (StringUtils.hasText(source.getType())) {
            copy.setType(source.getType());
        }
        copy.setExtension(source.getExtension());
        return copy;
    }

    private NodeResult executeNode(ChainNode node, Map<String, Object> chainContext) {
        LocalDateTime nodeStart = LocalDateTime.now();
        ToolExecutor.ToolRequest request = new ToolExecutor.ToolRequest(
                UUID.randomUUID().toString(), node.tool(), node.argumentsJson());
        List<ToolExecutor.ToolExecuteResponse> responses = toolExecutor.execute(List.of(request), chainContext);
        ToolExecutor.ToolExecuteResponse response = responses == null || responses.isEmpty() ? null : responses.getFirst();
        return new NodeResult(node, nodeStart, LocalDateTime.now(), response);
    }

    /** 定时触发单个节点：绑定取消令牌后执行，把结果（或异常）交给对应 future */
    private void runNodeOnScheduler(ChainNode node, Map<String, Object> chainContext,
                                    ChatCancelToken token, CompletableFuture<NodeResult> future) {
        if (token != null) {
            ChatCancelContext.bind(token);
            token.registerThread(Thread.currentThread());
        }
        try {
            future.complete(executeNode(node, chainContext));
        } catch (Throwable t) {
            future.completeExceptionally(t);
        } finally {
            if (token != null) {
                token.unregisterThread(Thread.currentThread());
                ChatCancelContext.unbind();
            }
        }
    }

    /** 按链顺序收集各节点结果；节点异常降级为失败响应，避免整条链失败 */
    private List<NodeResult> collectResults(List<ChainNode> chain, List<CompletableFuture<NodeResult>> futures) {
        List<NodeResult> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            ChainNode node = chain.get(i);
            try {
                results.add(futures.get(i).join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LocalDateTime now = LocalDateTime.now();
                results.add(new NodeResult(node, now, now,
                        new ToolExecutor.ToolExecuteResponse(name(),
                                "工具[" + node.tool() + "]执行异常，原因是：" + cause.getMessage()).setSucceed(false)));
            }
        }
        return results;
    }

    private static final AtomicInteger SCHEDULER_SEQ = new AtomicInteger();

    /** 调度线程守护化，避免阻塞中的节点拖住 JVM 退出 */
    private static final class WaitSchedulerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "wait-flow-sched-" + SCHEDULER_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    // ======================== 时间与结果 ========================

    private static void sleepUntil(long startMillis, long targetOffsetMillis) throws InterruptedException {
        long remain = startMillis + targetOffsetMillis - System.currentTimeMillis();
        if (remain > 0) {
            Thread.sleep(remain);
        }
    }

    private ToolExecutor.ToolExecuteResponse buildResponse(long milliseconds, long startMillis,
                                                           LocalDateTime startTime, List<NodeResult> nodeResults) {
        LocalDateTime endTime = LocalDateTime.now();
        // 工具执行可能超过等待窗口，故分别给出「预计」与「实际」等待时长
        long actualMillis = Math.max(0L, System.currentTimeMillis() - startMillis);
        long overrunMillis = actualMillis - milliseconds;

        StringBuilder sb = new StringBuilder();
        sb.append("""
                ```flow
                工具[wait_flow_tool]等待**完成**。
                [预计等待]：${expected} 毫秒
                [实际等待]：${actual} 毫秒
                [开始时间]：${startTime}
                [结束时间]：${endTime}
                ${overrun}
                ```
                """
                .replace("${expected}", String.valueOf(milliseconds))
                .replace("${actual}", String.valueOf(actualMillis))
                .replace("${startTime}", startTime.format(DATE_TIME_FORMATTER))
                .replace("${endTime}", endTime.format(DATE_TIME_FORMATTER))
                .replace("${overrun}", overrunMillis > 0
                        ? "[超出预计]：+" + overrunMillis + " 毫秒（有工具执行时间超过等待窗口）"
                        : "[超出预计]：无"));

        List<MultimodalContent> multimodalContents = new ArrayList<>();
        if (!nodeResults.isEmpty()) {
            sb.append("\n## 工具链执行结果\n");
            for (NodeResult result : nodeResults) {
                ChainNode node = result.node();
                ToolExecutor.ToolExecuteResponse response = result.response();
                boolean succeed = response != null && response.isSucceed();
                long nodeMillis = Duration.between(result.start(), result.end()).toMillis();
                sb.append("\n### [").append(node.at()).append("ms] 工具[").append(node.tool()).append("] ")
                        .append(succeed ? "成功" : "失败").append("\n")
                        .append("- 开始时间：").append(result.start().format(DATE_TIME_FORMATTER)).append("\n")
                        .append("- 结束时间：").append(result.end().format(DATE_TIME_FORMATTER)).append("\n")
                        .append("- 耗时：").append(nodeMillis).append(" 毫秒\n");
                if (response != null && StringUtils.hasText(response.getResult())) {
                    sb.append("\n").append(response.getResult()).append("\n");
                }
                if (response != null) {
                    // 直接聚合子工具的多模态结果：其内容已由各自落盘并回写为可移植引用，这里只带引用与类型
                    for (MultimodalContent content : response.getMultimodalContents()) {
                        multimodalContents.add(new MultimodalContent(content.getPathOrText(), content.getType(), null));
                    }
                }
            }
        }

        ToolExecutor.ToolExecuteResponse response = new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        if (!multimodalContents.isEmpty()) {
            response.setMultimodalContents(multimodalContents);
        }
        return response;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    /** 已校验的工具链节点 */
    private record ChainNode(String tool, long at, String argumentsJson) {
    }

    /** 单个节点的执行结果 */
    private record NodeResult(ChainNode node, LocalDateTime start, LocalDateTime end, ToolExecutor.ToolExecuteResponse response) {
    }

    @Data
    private static class Arguments {
        private long milliseconds;
        private List<ChainNodeArg> toolChain;
    }

    @Data
    @Accessors(chain = true)
    private static class ChainNodeArg {
        private String tool;
        private Long at;
        private Map<String, Object> arguments;
    }
}
