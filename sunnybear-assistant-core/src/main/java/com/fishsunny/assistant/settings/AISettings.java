package com.fishsunny.assistant.settings;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 18:11
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AISettings {

    public static final String CHAT = "chat";
    public static final String CHAT_PRO = "chat_pro";
    public static final String OCR = "ocr";
    public static final String MISSION = "mission";
    public static final String TASK = "task";
    /** 最轻量级任务使用的 AI（如标题生成），层级：主AI > 任务AI > cub */
    public static final String CUB = "cub";

    private String prompt;
    public AISettings setPrompt(String prompt) {
        this.prompt = prompt == null ? "" : prompt;
        return this;
    }

    private String adapterName;
    public AISettings setAdapterName(String adapterName) {
        this.adapterName = adapterName == null ? "" : adapterName;
        return this;
    }

    private String model;
    public AISettings setModel(String model) {
        this.model = model == null ? "" : model;
        return this;
    }

    private Boolean stream;
    public AISettings setStream(Boolean stream) {
        this.stream = Boolean.TRUE.equals(stream);
        return this;
    }

    private Boolean thinking;
    public AISettings setThinking(Boolean thinking) {
        this.thinking = Boolean.TRUE.equals(thinking);
        return this;
    }

    // low / high / max
    private String reasoningEffort;

    public AISettings setReasoningEffort(String reasoningEffort) {
        if (!StringUtils.hasText(reasoningEffort)) {
            this.reasoningEffort = "high";
            return this;
        }
        switch (reasoningEffort) {
            case "low":
                this.reasoningEffort = "low";
                break;
            case "max":
                this.reasoningEffort = "max";
                break;
            case "high":
                this.reasoningEffort = "high";
                break;
            default:
                throw new IllegalArgumentException("Invalid reasoningEffort: " + reasoningEffort);
        }
        return this;
    }

    //介于 -2.0 和 2.0 之间的数字。如果该值为正，那么新 token 会根据其在已有文本中的出现频率受到相应的惩罚，降低模型重复相同内容的可能性
    //Default = 0
    @Deprecated
    private Double frequencyPenalty;

    //介于 1 到 8192 间的整数，限制一次请求中模型生成 completion 的最大 token 数。输入 token 和输出 token 的总长度受模型的上下文长度的限制
    //Default = 4096
    @Deprecated
    private Integer maxTokens;

    //介于 -2.0 和 2.0 之间的数字。如果该值为正，那么新 token 会根据其是否已在已有文本中出现受到相应的惩罚，从而增加模型谈论新主题的可能性。
    //Default = 0;
    @Deprecated
    private Double presencePenalty;

    /** 用于系统内部在特定场景下强制输出模式。可选值：json_object、json_schema。非空时设置到 API 的 response_format */
    private String responseFormat;

    //采样温度，介于 0 和 2 之间。更高的值，如 0.8，会使输出更随机，而更低的值，如 0.2，会使其更加集中和确定。 我们通常建议可以更改这个值或者更改 top_p，但不建议同时对两者进行修改。
    //Default = 1;
    @Deprecated
    private Double temperature;

    //作为调节采样温度的替代方案，模型会考虑前 top_p 概率的 token 的结果。所以 0.1 就意味着只有包括在最高 10% 概率中的 token 会被考虑。 我们通常建议修改这个值或者更改 temperature，但不建议同时对两者进行修改。
    //Default = 1;
    @Deprecated
    private Double top_p;

    /** 厂商/自定义扩展字段（JSON 对象）。对话时原样展开到请求体顶层；与内置已映射参数同名的会被忽略（内置优先） */
    private Map<String, Object> customFields = new HashMap<>();
    public AISettings setCustomFields(Map<String, Object> customFields) {
        this.customFields = customFields == null ? new HashMap<>() : customFields;
        return this;
    }
    public Map<String, Object> getCustomFields() {
        return this.customFields == null ? new HashMap<>() : this.customFields;
    }

    public AISettings() {
    }

    public AISettings copy(AISettings settings) {
        return this.setPrompt(settings.getPrompt())
                .setAdapterName(settings.getAdapterName())
                .setModel(settings.getModel())
                .setStream(settings.getStream())
                .setThinking(settings.getThinking())
                .setReasoningEffort(settings.getReasoningEffort())
                .setFrequencyPenalty(settings.getFrequencyPenalty())
                .setMaxTokens(settings.getMaxTokens())
                .setPresencePenalty(settings.getPresencePenalty())
                .setResponseFormat(settings.getResponseFormat())
                .setTemperature(settings.getTemperature())
                .setTop_p(settings.getTop_p())
                .setCustomFields(settings.getCustomFields());
    }

    public AISettings json() {
        this.setResponseFormat("json_object");
        return this;
    }
}
