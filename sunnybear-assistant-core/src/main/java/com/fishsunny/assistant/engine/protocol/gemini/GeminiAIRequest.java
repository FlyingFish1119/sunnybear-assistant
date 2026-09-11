package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiContent;
import com.fishsunny.assistant.engine.protocol.gemini.tools.GeminiTool;
import com.fishsunny.assistant.engine.protocol.gemini.tools.GeminiToolConfig;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * generateContent / streamGenerateContent 请求体。
 * <p>与 OpenAI、Anthropic 最大的不同：<b>模型名不在请求体里</b>，而是拼在 URL 路径上
 * （{@code /v1beta/models/{model}:generateContent}），流式与否也由路径后缀决定，
 * 所以 model 字段只是适配器内部用来拼 URL 的载体，标记 @JsonIgnore 不进报文。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiAIRequest implements AIRequest {

    /** 仅用于拼 URL，不序列化 */
    @JsonIgnore
    private String model;

    private List<GeminiContent> contents = new ArrayList<>();

    /** 系统提示词：Gemini 没有 system 角色，走顶层独立字段 */
    private GeminiContent systemInstruction;

    /** 空列表会被拒，无工具时保持 null */
    private List<GeminiTool> tools;

    private GeminiToolConfig toolConfig;

    private GeminiGenerationConfig generationConfig;

    private List<Map<String, Object>> safetySettings;

    /**
     * 适配器自己会写入的顶层 key，同名自定义字段一律丢弃（内置优先）——直接走 @JsonAnyGetter
     * 展开会和这些属性撞出重复 key。safetySettings 虽然由适配器写入，但它支持被 customFields
     * 覆盖，覆盖逻辑在适配器里，不走这里。cachedContent / store 之类没有内置来源的字段不列入，
     * 留给 customFields 透传。
     */
    private static final Set<String> RESERVED_BODY_KEYS = Set.of(
            "contents", "systemInstruction", "tools", "toolConfig", "generationConfig", "safetySettings");

    /** 厂商自定义字段（模型设置里的 customFields），经 setExtraBody 过滤后展开为请求体顶层字段 */
    @JsonIgnore
    private Map<String, Object> extraBody = new LinkedHashMap<>();

    public GeminiAIRequest setExtraBody(Map<String, Object> extra) {
        this.extraBody = new LinkedHashMap<>();
        if (extra != null) {
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                if (!RESERVED_BODY_KEYS.contains(entry.getKey())) {
                    this.extraBody.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> anyBodyExtra() {
        return extraBody;
    }

    public GeminiAIRequest() {
    }
}
