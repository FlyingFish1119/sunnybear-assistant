package com.fishsunny.assistant.engine.tool.service.background;

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolResponseHandleChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 后台任务结果的待投递总线。
 * <p>
 * 场景：异步工具（后台命令、长任务）先返回「已提交」，真正的产出稍后才到。产出方拿会话 id
 * 调用 {@link #post} 把结果推进总线，等下一次「有消息落地」的时机由消费方取走，
 * 作为**正文之后的追加文本块**挂到消息末尾 —— 与「首块 = 正文、其后块只展示不编辑」的
 * 消息语义一致。
 * <p>
 * 总线只承载「哪个工具」+「什么内容」，不持有任何工具执行模型的引用：产出方不必为了投递
 * 去构造一个工具响应对象，将来也不需要知道结果会落到哪条消息上。
 * <p>
 * 两个消费时机：
 * <ol>
 *   <li><b>工具链</b>：本类实现 {@link ToolResponseHandleChain}，每批工具执行完都会被回调一次，
 *       待投递内容并进本批最后一条工具响应，随该批工具消息一起落库并补推前端；</li>
 *   <li><b>消息落库点</b>：ChatProcessor / ServiceProcessor 在 append 消息前调用
 *       {@link #attachPending}，把内容挂到即将落库的那条消息末尾。</li>
 * </ol>
 * 任一消费点取走即删，两个时机互不重复；没人消费就一直留在内存队列里等下一次时机
 * （进程重启即丢失，这是刻意的取舍：后台结果属于「即时补充」，不值得建表管状态）。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/21 13:33
 */
@Slf4j
@Component
public class BackgroundToolResponseBus implements ToolResponseHandleChain {

    /** 会话 -> 待投递结果队列：后台任务线程写入、处理链线程取走，两级容器都必须并发安全 */
    private final Map<String, Queue<PendingResult>> pendingMap = new ConcurrentHashMap<>();

    /**
     * 投递一条后台任务结果（产出方调用）。
     *
     * @param sessionId 目标会话 id；为空则丢弃（结果无处可投）
     * @param toolName  产出该结果的工具名，进投递文案前缀
     * @param text      结果正文
     */
    public void post(String sessionId, String toolName, String text) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(text)) {
            return;
        }
        pendingMap.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>())
                .add(new PendingResult(toolName, text));
        log.debug("后台任务结果已入总线: sessionId={}, tool={}", sessionId, toolName);
    }

    @Override
    public void handle(List<ToolExecutor.ToolExecuteResponse> batchResponses, Map<String, Object> context) {
        // 本批没有工具响应可挂载：原地保留，等下一次时机
        if (CollectionUtils.isEmpty(batchResponses)) {
            return;
        }
        String text = drain(resolveSessionId(context));
        if (!StringUtils.hasText(text)) {
            return;
        }
        // 追加成本批最后一条工具响应的额外内容块（转换后即消息正文之后的那个 text 块）
        batchResponses.getLast().appendTextContent(text);
    }

    /**
     * 把待投递内容挂到消息末尾（消息落库前调用）。
     * <p>
     * 取的是该会话当前全部待投递结果，合成一段文本作为新的文本块；没有则原样不动。
     *
     * @param message 即将落库的消息
     */
    public void attachPending(ChatMessage message) {
        if (message == null || message.getContents() == null) {
            return;
        }
        String text = drain(message.getSessionId());
        if (!StringUtils.hasText(text)) {
            return;
        }
        message.getContents().add(new TextContent(text));
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 取走该会话的全部待投递结果，合成一段文本。
     * <p>
     * 取一条删一条（poll），因此与 {@link #post} 并发时最多丢一次投递、不会重复投递。
     *
     * @param sessionId 会话 id
     * @return 合成后的文本；没有待投递内容时返回 null
     */
    private String drain(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        Queue<PendingResult> queue = pendingMap.get(sessionId);
        if (queue == null) {
            return null;
        }
        List<String> blocks = new ArrayList<>();
        PendingResult pending;
        while ((pending = queue.poll()) != null) {
            blocks.add(format(pending));
        }
        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    /** 投递文案：「xxx」工具的后台任务已完成 + 正文 */
    private String format(PendingResult pending) {
        String toolName = StringUtils.hasText(pending.toolName()) ? pending.toolName() : "未知工具";
        return "「" + toolName + "」工具的后台任务已完成：\n" + pending.text();
    }

    /** 从工具执行上下文里取当前会话 id */
    private String resolveSessionId(Map<String, Object> context) {
        Object value = context == null ? null : context.get("chatSession");
        return value instanceof ChatSession chatSession ? chatSession.getId() : null;
    }

    /** 待投递的一条结果：来源工具 + 正文 */
    private record PendingResult(String toolName, String text) {
    }
}
