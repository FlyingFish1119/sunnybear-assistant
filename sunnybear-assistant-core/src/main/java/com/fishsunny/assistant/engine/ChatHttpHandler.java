package com.fishsunny.assistant.engine;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 19:04
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.HandleTTSAble;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Slf4j
@Component
public class ChatHttpHandler {

    private final ObjectMapper objectMapper;
    private final AIAdapterFactory adapterFactory;

    /**
     * 空闲超时毫秒数：对端超过该时长没有发送有效消息（完全静默或只发 keep-alive 等保活事件）
     * 时放弃本次流式连接并抛异常。<= 0 表示关闭该保护（保持旧的无限等待行为）。
     */
    private final long idleTimeoutMillis;

    /** 泵线程与主线程之间缓冲的行数，兼作背压 */
    private static final int QUEUE_CAPACITY = 1024;

    @Getter
    private static final Set<String> PASS_SIGN = ConcurrentHashMap.newKeySet();

    @Autowired
    public ChatHttpHandler(ObjectMapper objectMapper, AIAdapterFactory adapterFactory,
                           @Value("${engine.chat.idle-timeout-s:30}") long idleTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.adapterFactory = adapterFactory;
        this.idleTimeoutMillis = idleTimeoutSeconds > 0 ? idleTimeoutSeconds * 1000L : 0;
    }


    public record TranslateData(
            String passId,
            String adapterName,
            AIRequest request
    ) {
    }

    public record TranslateHandler(
            InTranslateCallback inTranslate,
            CompleteCallback complete
    ) {
    }

    /**
     * 翻译的可选参数。stream 原先是 TranslateData 的字段，为保持 data 语义纯净（只描述
     * "译什么/给谁译"）而拆到这里：option 描述"怎么译"。
     */
    @Data
    @Accessors(chain = true)
    public static class TranslateOption {
        private Boolean stream = false;
        /**
         * 朗读开关：开启且 adapter 实现 HandleTTSAble（TTSClient 由工厂在 enable 时注入）才朗读。
         * 句音频经 InTranslateCallback.onTTSAudio 逐句交还调用方；整轮完整音频在
         * complete 回调的 TranslateResult.audioBase64 里。
         */
        private Boolean enableTTS = false;
    }

    public void translate(TranslateData data, TranslateHandler handler, TranslateOption option) throws Exception {
        String passId = data.passId();
        String adapterName = data.adapterName();
        Boolean stream = option.getStream();
        AIRequest request = data.request();
        InTranslateCallback inTranslate = handler.inTranslate();
        CompleteCallback onComplete = handler.complete();
        PASS_SIGN.add(passId);

        boolean safeStream = stream != null && stream;
        AIAdapter adapter = adapterFactory.getAdapter(adapterName, safeStream);

        HandleTTSAble ttsAble = Boolean.TRUE.equals(option.getEnableTTS()) && adapter instanceof HandleTTSAble handle ? handle : null;

        // 空闲/无效信息超时只作用于流式 SSE：流式对端靠有效 data 消息/keep-alive 保活，
        // 静默才判定超时。非流式是普通 HTTP 整包响应，本身没有保活机制，服务端思考较久时
        // 迟迟不发体属正常现象，不应被误判为"无效信息静默"，故非流式不启用该保护（无限等待）。
        long idleMillis = safeStream ? idleTimeoutMillis : 0;

        // 泵线程把底层流的行按顺序推入有界队列：对端完全静默时主线程仍能按空闲超时醒来
        // 并放弃连接，而不是无限阻塞在流的迭代器上（Stream.iterator().hasNext() 不支持超时）。
        // connect 也放进泵线程，响应头迟迟不返回时主线程同样会被超时释放。
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Stream<String>> streamRef = new AtomicReference<>();
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        Thread pump = new Thread(
                () -> pumpLines(adapter, request, cancelled, streamRef, queue),
                "stream-pump" + (StringUtils.hasText(passId) ? "-" + passId : ""));
        pump.setDaemon(true);
        pump.start();

        long deadline = idleMillis > 0 ? System.currentTimeMillis() + idleMillis : 0;
        AIResponse lastRes = null;
        // 流正常走完（最后一个 chunk finished=true）才合成整轮完整音频；
        // 中断/流早断/出错时不做（避免把半截回复的语音塞进消息）
        boolean streamCompleted = false;
        try {
            while (true){
                // 如果 ID 存在，则认为被中断
                if (!PASS_SIGN.contains(passId)) {
                    break; // 用户中断：TTS 收尾统一走 finally 的 cancelTTS
                }

                StreamEvent event = waitEvent(queue, idleMillis, deadline, adapterName);
                if (event.error() != null) {
                    // 泵线程建连/读取失败，抛给调用方（与旧的同步抛错语义一致）
                    throw new RuntimeException(event.error());
                }
                if (event.line() == null) {
                    break; // 流正常结束
                }
                String line = event.line();
                if (!StringUtils.hasText(line) || line.equals("\n")) {
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                // Anthropic SSE 使用 event: 行标记事件类型，实际数据在 data: 行中
                if (line.startsWith("event: ")) {
                    continue;
                }
                if (line.startsWith("data: ")) {
                    line = line.substring("data: ".length());
                }
                // 去前缀后为空（如空 keepalive），跳过
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                AIResponse response = objectMapper.readValue(line, adapter.getTargetRespCls());
                lastRes = response;

                // 重置空闲计时
                if (idleMillis > 0) {
                    deadline = System.currentTimeMillis() + idleMillis;
                }

                // 处理可能的工具调用
                adapter.collectChunk(response);

                // 文本先行推给调用方，之后才轮到 TTS（同步合成会短暂阻塞收流循环）
                AIResponse converted = adapter.convertToMaster(response);
                if (inTranslate != null) {
                    inTranslate.onTranslate(converted);
                }

                boolean finished = adapter.finished(response);

                if (ttsAble != null) {
                    String audio = ttsAble.onTTSChunk(response, finished);
                    if (audio != null && inTranslate != null) {
                        inTranslate.onTTSAudio(audio);
                    }
                }
                if (finished) {
                    streamCompleted = true;
                    break;
                }
            }
            // 流意外结束或中断，回传已收集的内容
            if (onComplete != null) {
                AIResponse lastConverted = lastRes != null ? adapter.convertToMaster(lastRes) : null;

                // 整轮完整音频（供调用方入库，如塞进 assistant 消息 extension）
                String fullAudio = ttsAble != null && streamCompleted ? ttsAble.fullAudioBase64() : null;
                TranslateResult result = new TranslateResult(
                        adapter.getReasoning(),
                        adapter.getContent(),
                        adapter.getToolCalls(),
                        adapter.getReasoningSignature(),
                        fullAudio
                );
                onComplete.onComplete(result, lastConverted);
            }
        } finally {
            // 结束/中断/超时都通知泵线程停止并尽力关闭底层流，Stream.close() 会取消订阅
            // 释放连接与 JDK HttpClient 的 Direct ByteBuffer
            stopPump(cancelled, streamRef, pump);
            // TTS 兜底收尾：正常收流已在最后一个 chunk（finished=true）冲刷过缓冲；
            // 中断/异常/流早断路径靠它丢弃残留缓冲。幂等，无副作用
            if (ttsAble != null) {
                ttsAble.cancelTTS();
            }
            PASS_SIGN.remove(passId);
        }
    }

    /**
     * 从队列取下一个事件。启用空闲超时时等待不会超过剩余时限，超时抛出
     * {@link StreamIdleTimeoutException}；关闭保护（idleMillis <= 0）时无限等待，与旧行为一致。
     */
    private static StreamEvent waitEvent(BlockingQueue<StreamEvent> queue, long idleMillis,
                                         long deadline, String adapterName)
            throws InterruptedException, StreamIdleTimeoutException {
        if (idleMillis <= 0) {
            return queue.take();
        }
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            throw new StreamIdleTimeoutException((int) (idleMillis / 1000), adapterName);
        }
        StreamEvent event = queue.poll(remain, TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new StreamIdleTimeoutException((int) (idleMillis / 1000), adapterName);
        }
        return event;
    }

    /**
     * 泵线程主循环：负责建连与读取底层流，把原始行推给主线程解析。流耗尽时投递结束事件；
     * 异常时投递错误事件（除非连接已被主线程放弃）。
     */
    private static void pumpLines(AIAdapter adapter, AIRequest request,
                                  AtomicBoolean cancelled, AtomicReference<Stream<String>> streamRef,
                                  BlockingQueue<StreamEvent> queue) {
        try {
            Stream<String> lines = adapter.connect(request);
            streamRef.set(lines);
            try (lines) {
                Iterator<String> it = lines.iterator();
                while (!cancelled.get() && it.hasNext()) {
                    queue.put(StreamEvent.ofLine(it.next()));
                }
            }
            // 正常耗尽：通知主线程结束（已被取消则主线程已离开，无需投递）
            if (!cancelled.get()) {
                queue.put(StreamEvent.ofEnd());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 建连失败或读取中断（如连接被服务端掐断），尽力投递错误事件
            if (!cancelled.get() && !queue.offer(StreamEvent.ofError(t))) {
                log.error("Failed to offer error event to queue", t);
            }
        }
    }

    /**
     * 通知泵线程停止并尽力关闭底层流。泵线程是守护线程：即使对端静默到连中断都无法唤醒它
     * （极端情况，如 connect 阶段就挂死），最多泄漏一个线程，不会拖住聊天线程池。
     */
    private static void stopPump(AtomicBoolean cancelled, AtomicReference<Stream<String>> streamRef, Thread pump) {
        cancelled.set(true);
        Stream<String> lines = streamRef.get();
        if (lines != null) {
            lines.close();
        }
        pump.interrupt();
    }

    /**
     * 泵线程与主线程之间的传输单元：line 为一行原始数据；error 为建连/读取异常；
     * 两者皆空表示流正常结束。
     */
    private record StreamEvent(String line, Throwable error) {

        static StreamEvent ofLine(String line) {
            return new StreamEvent(line, null);
        }

        static StreamEvent ofError(Throwable error) {
            return new StreamEvent(null, error);
        }

        static StreamEvent ofEnd() {
            return new StreamEvent(null, null);
        }
    }


    /**
     * 翻译数据，根据 stream 参数选择流式或非流式适配器。
     * 顺序为 inTranslate -> complete -> functionCall
     * @param stopId 中断标识（用于流式中断控制）
     * @param adapterName 适配器名称
     * @param request 请求体
     * @param stream 是否使用流式处理
     * @param inTranslate 翻译过程，参数为翻译后的数据
     * @param onComplete 传输完成，参数是工具调用列表
     */
    @Deprecated
    public void translate(String stopId, String adapterName, AIRequest request,
                          Boolean stream,
                          InTranslateCallback inTranslate,
                          CompleteCallback onComplete) throws Exception {
        translate(new TranslateData(stopId, adapterName, request),
                new TranslateHandler(inTranslate, onComplete),
                new TranslateOption().setStream(stream));
    }

    @Deprecated
    public void translate(String adapterName, AIRequest request, Boolean stream,
                          InTranslateCallback inTranslate,
                          CompleteCallback onComplete) throws Exception {
        translate(new TranslateData("", adapterName, request),
                new TranslateHandler(inTranslate, onComplete),
                new TranslateOption().setStream(stream));
    }

    public interface CompleteCallback {
        void onComplete(TranslateResult result, AIResponse lastRes);
    }

    /**
     * 翻译完成后承载所有结果的记录，避免接口膨胀。
     * 新增字段时只需加在这里，调用方零改动。
     *
     * @param audioBase64 整轮完整音频（mp3 base64，enableTTS 且合成成功时非空；
     *                    供调用方入库，如塞进 assistant 消息的 extension）
     */
    public record TranslateResult(
        String reasoning,
        String content,
        List<AIAdapter.ToolCall> toolCalls,
        String reasoningSignature,
        String audioBase64
    ) {}

    public interface InTranslateCallback {
        void onTranslate(AIResponse response);

        /**
         * 朗读音频回调，与 {@link #onTranslate} 同节奏逐句触发（配合 enableTTS 使用）。
         * 参数为一句可朗读文本合成出的音频 mp3 base64（格式见 engine.tts.format）。
         * 默认 no-op；由持有输出通道（如 WebSocket 会话）的调用方覆写决定怎么推。
         */
        default void onTTSAudio(String audioBase64) {
        }
    }
}
