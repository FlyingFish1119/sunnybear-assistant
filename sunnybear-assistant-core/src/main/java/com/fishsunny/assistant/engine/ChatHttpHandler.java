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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

@Component
public class ChatHttpHandler {

    private final ObjectMapper objectMapper;
    private final AIAdapterFactory adapterFactory;

    @Getter
    private static final Set<String> allowedContinue = new ConcurrentSkipListSet<>();

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
    ) {}

    public record TranslateHandler(
            InTranslateCallback inTranslate,
            CompleteCallback complete
    ) {}

    public void translate(TranslateData data, TranslateHandler handler) throws Exception {
        translate(data.stopId(), data.adapterName(), data.request(), data.stream(), handler.inTranslate(), handler.complete());
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
        allowedContinue.add(stopId);
        stream = stream != null && stream;
        AIAdapter adapter = adapterFactory.getAdapter(adapterName, stream);
        try (InputStream inputStream = adapter.connect(request)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            AIResponse lastRes = null;
            while ((line = reader.readLine()) != null) {
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

                // 如果 ID 被移除，则认为被中断
                if (!allowedContinue.contains(stopId)) {
                    break;
                }
                if (adapter.finished(response)) {
                    break;
                }
            }
            // 流意外结束（readLine 返回 null）但未触发 finished → 回传已收集的内容
            if (onComplete != null) {
                AIResponse lastConverted = lastRes != null ? adapter.convertToMaster(lastRes) : null;
                TranslateResult result = new TranslateResult(
                        adapter.getReasoning(), adapter.getContent(),
                        adapter.getToolCalls(), adapter.getReasoningSignature());
                onComplete.onComplete(result, lastConverted);
            }
        } finally {
            allowedContinue.remove(stopId);
        }
    }

    public static interface CompleteCallback {
        void onComplete(TranslateResult result, AIResponse lastRes);
    }

    /**
     * 翻译完成后承载所有结果的记录，避免接口膨胀。
     * 新增字段时只需加在这里，调用方零改动。
     */
    public static record TranslateResult(
        String reasoning,
        String content,
        List<AIAdapter.ToolCall> toolCalls,
        String reasoningSignature
    ) {}

    public static interface  InTranslateCallback {
        public void onTranslate(AIResponse response);
    }
}
