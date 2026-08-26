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
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Component
public class ChatHttpHandler {

    private final ObjectMapper objectMapper;
    private final AIAdapterFactory adapterFactory;

    @Getter
    private static final Set<String> STOP_SIGN = ConcurrentHashMap.newKeySet();

    @Autowired
    public ChatHttpHandler(ObjectMapper objectMapper, AIAdapterFactory adapterFactory) {
        this.objectMapper = objectMapper;
        this.adapterFactory = adapterFactory;
    }


    public record TranslateData(
            String stopId,
            String adapterName,
            Boolean stream,
            AIRequest request
    ) {
    }

    public record TranslateHandler(
            InTranslateCallback inTranslate,
            CompleteCallback complete
    ) {
    }

    public void translate(TranslateData data, TranslateHandler handler) throws Exception {
        String stopId = data.stopId();
        String adapterName = data.adapterName();
        Boolean stream = data.stream();
        AIRequest request = data.request();
        InTranslateCallback inTranslate = handler.inTranslate();
        CompleteCallback onComplete = handler.complete();

        if (STOP_SIGN.contains(stopId)) {
            STOP_SIGN.remove(stopId);
            return;
        }

        boolean safeStream = stream != null && stream;
        AIAdapter adapter = adapterFactory.getAdapter(adapterName, safeStream);
        try (Stream<String> lines = adapter.connect(request)) {
            AIResponse lastRes = null;
            for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                // 如果 ID 存在，则认为被中断
                if (STOP_SIGN.contains(stopId)) {
                    STOP_SIGN.remove(stopId);
                    break;
                }
                String line = it.next();
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

                // 处理可能的工具调用
                adapter.collectChunk(response);

                if (inTranslate != null) {
                    inTranslate.onTranslate(adapter.convertToMaster(response));
                }
                if (adapter.finished(response)) {
                    break;
                }
            }
            // 流意外结束或中断，回传已收集的内容
            // Stream.close() 会正确取消订阅，JDK HttpClient 释放 Direct ByteBuffer
            if (onComplete != null) {
                AIResponse lastConverted = lastRes != null ? adapter.convertToMaster(lastRes) : null;
                TranslateResult result = new TranslateResult(
                        adapter.getReasoning(), adapter.getContent(),
                        adapter.getToolCalls(), adapter.getReasoningSignature());
                onComplete.onComplete(result, lastConverted);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            STOP_SIGN.remove(stopId);
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
    public void translate(String stopId, String adapterName, AIRequest request,
                          Boolean stream,
                          InTranslateCallback inTranslate,
                          CompleteCallback onComplete) throws Exception {
        translate(new TranslateData(stopId, adapterName, stream, request), new TranslateHandler(inTranslate, onComplete));
    }

    public void translate(String adapterName, AIRequest request, Boolean stream,
                          InTranslateCallback inTranslate,
                          CompleteCallback onComplete) throws Exception {
        translate(new TranslateData("", adapterName, stream, request), new TranslateHandler(inTranslate, onComplete));
    }

    public interface CompleteCallback {
        void onComplete(TranslateResult result, AIResponse lastRes);
    }

    /**
     * 翻译完成后承载所有结果的记录，避免接口膨胀。
     * 新增字段时只需加在这里，调用方零改动。
     */
    public record TranslateResult(
        String reasoning,
        String content,
        List<AIAdapter.ToolCall> toolCalls,
        String reasoningSignature
    ) {}

    public interface InTranslateCallback {
        void onTranslate(AIResponse response);
    }
}
