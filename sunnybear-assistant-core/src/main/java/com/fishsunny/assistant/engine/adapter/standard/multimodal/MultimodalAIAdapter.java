package com.fishsunny.assistant.engine.adapter.standard.multimodal;

/*
 * @Usage 多模态 tool 结果协议 —— 非流式适配器。
 *        独立协议族，不复用 Standard 适配器；响应侧复用 Standard 响应数据类（只读）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/2
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.adapter.HandleTTSAble;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.MultimodalAIRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.StandardAIResponse;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.response.StandardChoice;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.tts.TTSSpeaker;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class MultimodalAIAdapter extends MultimodalBaseAIAdapter implements HandleTTSAble {

    public MultimodalAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        if (!request.getClass().equals(super.masterReqCls)) {
            throw new RuntimeException("Invalid request class: " + request.getClass().getName());
        }
        ChatRequest chatRequest = (ChatRequest) request;
        MultimodalAIRequest multimodalRequest = new MultimodalAIRequest();

        ChatSettings settings = chatRequest.getSettings();

        String thinking = Boolean.TRUE.equals(settings.getThinking()) ? "enabled" : "disabled";
        multimodalRequest.setModel(settings.getModel())
                .setStream(false)  // 非流式
                .setThinking(new StandardAIThinking(thinking))
                .setReasoning_effort(settings.getReasoning_effort())
                .setFrequency_penalty(settings.getFrequency_penalty())
                .setMax_tokens(settings.getMax_tokens())
                .setPresence_penalty(settings.getPresence_penalty())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setMessages(convertToMultimodalMessage(chatRequest.getMessages()))
                .setTools(chatRequest.getTools());

        if (settings.getResponse_format() != null) {
            multimodalRequest.setResponse_format(
                    new MultimodalAIRequest.ResponseFormat(settings.getResponse_format().getType()));
        }

        multimodalRequest.setExtraBody(settings.getCustomFields());
        return multimodalRequest;
    }

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        if (!super.targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        StandardAIResponse standardAIResponse = (StandardAIResponse) response;
        ChatResponse chatResponse = new ChatResponse();

        StandardChoice[] choices = standardAIResponse.getChoices();
        List<StandardMessage> standardMessages = new ArrayList<>();
        for (StandardChoice choice : choices) {
            standardMessages.add(choice.getMessage());
        }

        chatResponse.setMessages(convertToChatMessage(standardMessages))
                .setStatus(ChatResponse.STATUS_DONE);

        return chatResponse;
    }

    @Override
    public boolean finished(AIResponse response) {
        return true;
    }

    // ------------------------------------------------------------------
    // TTS：HandleTTSAble 实现。响应侧复用 Standard 协议类：typed 的
    // StandardAssistantMessage 上 reasoning_content / content / tool_calls 独立成字段，
    // 直接抽取朗读，不做 master 转换。adapter 每 AI 回合一个实例 → 断句状态回合级独立。
    // ------------------------------------------------------------------

    /** 回合级句子合成器；懒建（工厂注入的 ttsClient 为 null 表示 TTS 未启用） */
    private TTSSpeaker ttsSpeaker;

    private TTSSpeaker tts() {
        if (ttsSpeaker == null && ttsClient != null) {
            ttsSpeaker = new TTSSpeaker(ttsClient);
        }
        return ttsSpeaker;
    }

    @Override
    public String onTTSChunk(AIResponse targetChunk, boolean finished) {
        TTSSpeaker speaker = tts();
        if (speaker == null) {
            return null;
        }
        if (!super.targetRespCls.equals(targetChunk.getClass())) {
            throw new RuntimeException("Invalid response class: " + targetChunk.getClass().getName());
        }
        StandardAIResponse standardAIResponse = (StandardAIResponse) targetChunk;
        // 本 chunk 产出的句音频（罕见多段时按序拼接——mp3 为帧流，串接可顺序解码）
        StringBuilder audio = new StringBuilder();
        for (StandardChoice choice : standardAIResponse.getChoices()) {
            if (choice.getMessage() instanceof StandardAssistantMessage assistantMessage && StringUtils.hasText(assistantMessage.getContent())) {
                // 非流式同一条消息可能既有正文又有工具调用：正文属于可朗读的 prose，
                // 工具参数在独立字段里不会混入；reasoning_content 独立成字段，天然不读
                String sentence = speaker.feedText(assistantMessage.getContent());
                if (sentence != null) {
                    audio.append(sentence);
                }
            }
        }
        if (finished) {
            // 流结束：冲刷剩余缓冲（尾部也会合成返回）
            String tail = speaker.flush();
            if (tail != null) {
                audio.append(tail);
            }
        }
        return StringUtils.hasText(audio) ? audio.toString() : null;
    }

    @Override
    public String fullAudioBase64() {
        // 整轮可读正文（collectChunk 已把全部正文收进 content）过滤代码后一次性合成，供入库
        return TTSSpeaker.synthesizeSpeakable(ttsClient, content.toString());
    }

    @Override
    public void cancelTTS() {
        if (ttsSpeaker != null) {
            ttsSpeaker.cancel();
        }
    }

    private final List<ToolCall> toolCallCache = new ArrayList<>();
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder content = new StringBuilder();

    @Override
    public boolean collectChunk(AIResponse response) {
        if (!targetRespCls.equals(response.getClass())) {
            throw new RuntimeException("Invalid response class: " + response.getClass().getName());
        }
        StandardAIResponse standardAIResponse = (StandardAIResponse) response;
        toolCallCache.clear();
        for (StandardChoice choice : standardAIResponse.getChoices()) {
            if (choice.getMessage() instanceof StandardAssistantMessage assistantMessage) {
                if (assistantMessage.getReasoning_content() != null) {
                    reasoning.append(assistantMessage.getReasoning_content());
                }
                if (assistantMessage.getContent() != null) {
                    content.append(assistantMessage.getContent());
                }
                List<StandardToolRequest> toolCalls = assistantMessage.getTool_calls();
                if (!CollectionUtils.isEmpty(toolCalls)) {
                    for (StandardToolRequest toolCall : toolCalls) {
                        ToolCall.Function function = new ToolCall.Function()
                                .setName(toolCall.getFunction().getName())
                                .setArguments(toolCall.getFunction().getArguments() == null ? "" : toolCall.getFunction().getArguments());
                        ToolCall adapterToolCall = new ToolCall()
                                .setIndex(toolCall.getIndex())
                                .setId(toolCall.getId())
                                .setFunction(function);
                        toolCallCache.add(adapterToolCall);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<ToolCall> getToolCalls() {
        return new ArrayList<>(toolCallCache);
    }

    @Override
    public String getReasoning() {
        return reasoning.toString();
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
        if (!MultimodalAIRequest.class.equals(targetCls)) {
            throw new RuntimeException("Invalid target class: " + targetCls.getName());
        }
        if (!StandardAIResponse.class.equals(targetRespCls)) {
            throw new RuntimeException("Invalid target response class: " + targetRespCls.getName());
        }
    }
}
