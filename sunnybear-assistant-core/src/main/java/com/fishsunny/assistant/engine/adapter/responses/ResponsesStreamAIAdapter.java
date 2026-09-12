package com.fishsunny.assistant.engine.adapter.responses;

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIRequest;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIResponse;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesContentPart;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesItem;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesStreamEvent;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responses API 流式适配器。
 *
 * <p>按事件类型累积，{@code output_index} 是 function_call 与其参数增量的关联键。
 * 三处容易踩的坑：
 * <ul>
 *   <li>{@code response.completed} <b>不能累积</b>——它内嵌的 response.output[] 会把已经流式收到的
 *       内容整份重复一遍，再累加就每个 token 都翻倍；它只作为终止信号。</li>
 *   <li>{@code function_call_arguments.done} 要<b>覆盖</b>而不是追加——它给的是完整 JSON，
 *       追加会和前面的增量碎片叠在一起。</li>
 *   <li>{@link #convertToMaster} 必须是纯函数：收流循环对终止事件会调它两次
 *       （循环内一次 + 收尾对 lastRes 再一次）。</li>
 * </ul>
 *
 * <p>思考内容逐帧下发给前端：前端只在 chunk/done 帧上累加 reasoningContent，落库的
 * init_assistant 消息只同步元数据、不覆盖正文，少了下发这一路思考面板要等刷新页面才看得见。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
public class ResponsesStreamAIAdapter extends ResponsesBaseAIAdapter {

    /** 按 output_index 累积的工具调用，LinkedHashMap 保证 getToolCalls() 与输出顺序一致 */
    private final Map<Integer, ToolCallMeta> toolCalls = new LinkedHashMap<>();

    /** output_index → item_id，args 事件按 item_id 关联时的反查表 */
    private final Map<Integer, String> itemIdByOutputIndex = new LinkedHashMap<>();

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningSignature;

    public ResponsesStreamAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    protected boolean streaming() {
        return true;
    }

    /** 在途的工具调用。id / name 在 output_item.added 时就已完整，只有 arguments 是碎片累积 */
    private static class ToolCallMeta {
        private final Integer outputIndex;
        private String itemId;
        private String callId;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCallMeta(Integer outputIndex) {
            this.outputIndex = outputIndex;
        }
    }

    // ==================== 累积 ====================

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!super.targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        ResponsesStreamEvent event = (ResponsesStreamEvent) response;
        String type = event.getType();
        if (type == null) {
            return false;
        }

        switch (type) {
            case ResponsesStreamEvent.TYPE_CREATED:
                reset();
                return false;

            case ResponsesStreamEvent.TYPE_OUTPUT_ITEM_ADDED: {
                ResponsesItem item = event.getItem();
                if (item == null) {
                    return false;
                }
                Integer outputIndex = event.getOutput_index();
                if (outputIndex != null && StringUtils.hasText(item.getId())) {
                    itemIdByOutputIndex.put(outputIndex, item.getId());
                }
                if (ResponsesItem.TYPE_FUNCTION_CALL.equals(item.getType()) && outputIndex != null) {
                    ToolCallMeta meta = new ToolCallMeta(outputIndex);
                    meta.itemId = item.getId();
                    meta.callId = item.getCall_id();
                    meta.name = item.getName();
                    if (StringUtils.hasText(item.getArguments())) {
                        meta.arguments.append(item.getArguments());
                    }
                    toolCalls.put(outputIndex, meta);
                }
                return false;
            }

            case ResponsesStreamEvent.TYPE_OUTPUT_TEXT_DELTA: {
                String delta = deltaText(event);
                if (delta != null) {
                    content.append(delta);
                }
                return false;
            }

            case ResponsesStreamEvent.TYPE_REASONING_SUMMARY_TEXT_DELTA: {
                String delta = deltaText(event);
                if (delta != null) {
                    reasoning.append(delta);
                }
                return false;
            }

            case ResponsesStreamEvent.TYPE_REASONING_SUMMARY_PART_ADDED:
            case ResponsesStreamEvent.TYPE_REASONING_SUMMARY_PART_DONE:
                // part.text 与 summary 的增量重复，累积会翻倍；只有整份摘要才有意义，
                // 而那一路已由 output_item.done 兜底
                return false;

            case ResponsesStreamEvent.TYPE_FUNCTION_CALL_ARGUMENTS_DELTA: {
                ToolCallMeta meta = resolveMeta(event);
                if (meta != null) {
                    String delta = deltaText(event);
                    if (delta != null) {
                        meta.arguments.append(delta);
                    }
                }
                return false;
            }

            case ResponsesStreamEvent.TYPE_FUNCTION_CALL_ARGUMENTS_DONE: {
                ToolCallMeta meta = resolveMeta(event);
                if (meta != null && event.getArguments() != null) {
                    // 覆盖而非追加：这里是完整 JSON，追加会和碎片叠在一起
                    meta.arguments.setLength(0);
                    meta.arguments.append(event.getArguments());
                }
                return false;
            }

            case ResponsesStreamEvent.TYPE_OUTPUT_ITEM_DONE: {
                applyFinalItem(event);
                return false;
            }

            case ResponsesStreamEvent.TYPE_COMPLETED:
                // 内嵌 response.output[] 是已流式内容的完整副本，累加会全部翻倍——只当终止信号
                if (event.getResponse() != null && event.getResponse().getUsage() != null) {
                    log.debug("Responses 流完成, usage={}", event.getResponse().getUsage());
                }
                return false;

            case ResponsesStreamEvent.TYPE_FAILED:
            case ResponsesStreamEvent.TYPE_INCOMPLETE:
                log.error("Responses 流以 {} 结束: {}", type, failureText(event));
                return false;

            case ResponsesStreamEvent.TYPE_ERROR:
                log.error("Responses SSE error: {}", event.getError());
                return false;

            default:
                log.debug("Responses SSE 未处理事件类型: {}", type);
                return false;
        }
    }

    /**
     * output_item.done 是 item 的权威快照，用它补齐/纠正累积结果。
     * <p>文本与摘要仅在尚未收到增量时才取用——正常路径下增量已累加完毕，再取一遍会翻倍；
     * 这一步是给「只发 item 不发增量」的网关兜底。
     */
    private void applyFinalItem(ResponsesStreamEvent event) {
        ResponsesItem item = event.getItem();
        if (item == null || item.getType() == null) {
            return;
        }
        switch (item.getType()) {
            case ResponsesItem.TYPE_FUNCTION_CALL -> {
                ToolCallMeta meta = resolveMeta(event);
                if (meta != null) {
                    if (StringUtils.hasText(item.getCall_id())) {
                        meta.callId = item.getCall_id();
                    }
                    if (StringUtils.hasText(item.getName())) {
                        meta.name = item.getName();
                    }
                    if (StringUtils.hasText(item.getArguments())) {
                        meta.arguments.setLength(0);
                        meta.arguments.append(item.getArguments());
                    }
                }
            }
            case ResponsesItem.TYPE_REASONING -> {
                if (reasoning.isEmpty() && item.getSummary() != null) {
                    for (ResponsesContentPart part : item.getSummary()) {
                        if (part != null && part.getText() != null) {
                            reasoning.append(part.getText());
                        }
                    }
                }
                // 服务端一个响应里可能有多个 reasoning item，取最后一个（与 Anthropic 签名同口径）
                if (StringUtils.hasText(item.getEncrypted_content())) {
                    reasoningSignature = item.getEncrypted_content();
                }
            }
            case ResponsesItem.TYPE_MESSAGE -> {
                if (content.isEmpty() && item.getContent() != null) {
                    for (ResponsesContentPart part : item.getContent()) {
                        if (part != null && ResponsesContentPart.TYPE_OUTPUT_TEXT.equals(part.getType())
                                && part.getText() != null) {
                            content.append(part.getText());
                        }
                    }
                }
            }
            default -> log.debug("Responses: 忽略未处理的 output_item.done 类型: {}", item.getType());
        }
    }

    /** 先按 output_index 找，找不到再按 item_id 反查；都没有就跳过而不抛错，避免个别网关的缺失拖垮整条流 */
    private ToolCallMeta resolveMeta(ResponsesStreamEvent event) {
        Integer outputIndex = event.getOutput_index();
        if (outputIndex != null) {
            ToolCallMeta meta = toolCalls.get(outputIndex);
            if (meta != null) {
                return meta;
            }
        }
        String itemId = event.getItem_id();
        if (StringUtils.hasText(itemId)) {
            for (ToolCallMeta meta : toolCalls.values()) {
                if (itemId.equals(meta.itemId)) {
                    return meta;
                }
            }
        }
        log.debug("Responses: 无法定位工具调用（output_index={}, item_id={}），跳过该增量",
                outputIndex, itemId);
        return null;
    }

    private String deltaText(ResponsesStreamEvent event) {
        return event.getDelta() == null || event.getDelta().isNull() ? null : event.getDelta().asText();
    }

    private void reset() {
        toolCalls.clear();
        itemIdByOutputIndex.clear();
        content.setLength(0);
        reasoning.setLength(0);
        reasoningSignature = null;
    }

    // ==================== 逐帧下发 ====================

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!super.targetRespCls.isAssignableFrom(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        ResponsesStreamEvent event = (ResponsesStreamEvent) response;
        ChatResponse chatResponse = new ChatResponse();

        String type = event.getType();
        if (type == null) {
            return chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
        }

        switch (type) {
            case ResponsesStreamEvent.TYPE_OUTPUT_TEXT_DELTA: {
                String delta = deltaText(event);
                if (delta == null) {
                    return chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                }
                return chatResponse
                        .setStatus(ChatResponse.STATUS_CHUNK)
                        .setMessages(List.of(new ChatMessage().setRole("assistant").text(delta)));
            }

            case ResponsesStreamEvent.TYPE_REASONING_SUMMARY_TEXT_DELTA: {
                String delta = deltaText(event);
                if (delta == null) {
                    return chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                }
                return chatResponse
                        .setStatus(ChatResponse.STATUS_CHUNK)
                        .setMessages(List.of(new ChatMessage()
                                .setRole("assistant")
                                .setReasoningContent(delta)));
            }

            case ResponsesStreamEvent.TYPE_FUNCTION_CALL_ARGUMENTS_DELTA:
            case ResponsesStreamEvent.TYPE_FUNCTION_CALL_ARGUMENTS_DONE: {
                ToolCallMeta meta = resolveMeta(event);
                if (meta == null) {
                    return chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
                }
                // 下发累积到当前的完整参数（与 Anthropic input_json_delta 分支同一口径）
                ChatToolRequest toolRequest = new ChatToolRequest()
                        .setId(meta.callId)
                        .setName(meta.name)
                        .setArguments(meta.arguments.toString());
                return chatResponse
                        .setStatus(ChatResponse.STATUS_CHUNK)
                        .setMessages(List.of(new ChatMessage()
                                .setRole("assistant")
                                .setToolCalls(List.of(toolRequest))));
            }

            case ResponsesStreamEvent.TYPE_COMPLETED:
                return chatResponse.setStatus(ChatResponse.STATUS_DONE);

            case ResponsesStreamEvent.TYPE_FAILED:
            case ResponsesStreamEvent.TYPE_INCOMPLETE:
                return chatResponse
                        .setStatus(ChatResponse.STATUS_ERROR)
                        .setMessages(List.of(new ChatMessage()
                                .setRole("assistant")
                                .text(failureText(event))));

            default:
                // created / in_progress / output_item.* / content_part.* 等只维持循环存活，
                // 让空闲超时计时器持续被重置
                return chatResponse.setStatus(ChatResponse.STATUS_CHUNK);
        }
    }

    private String failureText(ResponsesStreamEvent event) {
        if (event.getResponse() != null) {
            return describeFailure(event.getResponse());
        }
        return event.getError() != null
                ? "Responses 流式错误: " + event.getError()
                : "Responses 流式错误（事件类型 " + event.getType() + "）";
    }

    @Override
    public boolean finished(AIResponse response) {
        if (!targetRespCls.isAssignableFrom(response.getClass())) {
            return false;
        }
        String type = ((ResponsesStreamEvent) response).getType();
        return ResponsesStreamEvent.TYPE_COMPLETED.equals(type)
                || ResponsesStreamEvent.TYPE_FAILED.equals(type)
                || ResponsesStreamEvent.TYPE_INCOMPLETE.equals(type);
    }

    // ==================== 结果 ====================

    @Override
    public List<ToolCall> getToolCalls() {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallMeta meta : toolCalls.values()) {
            ToolCall.Function function = new ToolCall.Function()
                    .setName(meta.name)
                    .setArguments(meta.arguments.toString());
            calls.add(new ToolCall().setIndex(meta.outputIndex).setId(meta.callId).setFunction(function));
        }
        return calls;
    }

    @Override
    public String getReasoning() {
        return reasoning.toString();
    }

    @Override
    public String getReasoningSignature() {
        return reasoningSignature;
    }

    @Override
    public String getContent() {
        return content.toString();
    }

    @Override
    public void checkCls(Class<? extends AIRequest> masterCls,
                         Class<? extends AIRequest> targetCls,
                         Class<? extends AIResponse> masterRespCls,
                         Class<? extends AIResponse> targetRespCls) throws Exception {
        if (!ChatRequest.class.equals(masterCls)) {
            throw new RuntimeException("Invalid master class: " + masterCls.getName());
        }
        if (!ResponsesAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!ResponsesStreamEvent.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
