package com.fishsunny.assistant.engine.protocol.project.settings;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 07:47
 */

import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ChatSettings {

    private Boolean thinking;

    private Boolean stream;

    private String model;

    //介于 -2.0 和 2.0 之间的数字。如果该值为正，那么新 token 会根据其在已有文本中的出现频率受到相应的惩罚，降低模型重复相同内容的可能性
    //Default = 0
    private Double frequency_penalty;

    //介于 1 到 8192 间的整数，限制一次请求中模型生成 completion 的最大 token 数。输入 token 和输出 token 的总长度受模型的上下文长度的限制
    //Default = 4096
    private Integer max_tokens;

    //介于 -2.0 和 2.0 之间的数字。如果该值为正，那么新 token 会根据其是否已在已有文本中出现受到相应的惩罚，从而增加模型谈论新主题的可能性。
    //Default = 0;
    private Double presence_penalty;

    //采样温度，介于 0 和 2 之间。更高的值，如 0.8，会使输出更随机，而更低的值，如 0.2，会使其更加集中和确定。 我们通常建议可以更改这个值或者更改 top_p，但不建议同时对两者进行修改。
    //Default = 1;
    private Double temperature;

    //作为调节采样温度的替代方案，模型会考虑前 top_p 概率的 token 的结果。所以 0.1 就意味着只有包括在最高 10% 概率中的 token 会被考虑。 我们通常建议修改这个值或者更改 temperature，但不建议同时对两者进行修改。
    //Default = 1;
    private Double top_p;

    //推理努力程度，控制模型在推理时的思考深度，可选值：low / high / max
    private String reasoning_effort;

    /** 响应格式，对应 OpenAI response_format。null 表示不设置 */
    private ResponseFormat response_format;

    public ChatSettings() {
    }

    public ChatSettings(AISettings aiSettings) {
        this.thinking = aiSettings.getThinking();
        this.model = aiSettings.getModel();
        this.stream = aiSettings.getStream();
        this.frequency_penalty = aiSettings.getFrequencyPenalty();
        this.max_tokens = aiSettings.getMaxTokens();
        this.presence_penalty = aiSettings.getPresencePenalty();
        this.temperature = aiSettings.getTemperature();
        this.top_p = aiSettings.getTop_p();
        this.reasoning_effort = aiSettings.getReasoningEffort();
        if (aiSettings.getResponseFormat() != null) {
            this.response_format = new ResponseFormat(aiSettings.getResponseFormat());
        }
    }

    @Data
    @Accessors(chain = true)
    public static class ResponseFormat {
        private String type;

        public ResponseFormat() {}
        public ResponseFormat(String type) { this.type = type; }
    }
}
