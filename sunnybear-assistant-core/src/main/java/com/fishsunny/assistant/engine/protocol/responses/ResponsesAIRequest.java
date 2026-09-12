package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responses API（{@code POST /v1/responses}）请求体。
 *
 * <p>与 Chat Completions 形状的两处结构性差异，都在这里体现：
 * <ul>
 *   <li>系统提示词走顶层 {@code instructions}，不是 messages 里的 system 角色；</li>
 *   <li>{@code input} 是<b>平级 item 列表</b>（message / function_call / function_call_output /
 *       reasoning 互为兄弟），不是 role 数组。</li>
 * </ul>
 *
 * <p>{@code store} 固定 false：本项目自己把每条消息落库并每轮全量重发，服务端状态
 * （{@code previous_response_id}）会和项目自己的 session / 分支管理打架。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesAIRequest implements AIRequest {

    private String model;

    /** 系统提示词；空则省略（NON_NULL） */
    private String instructions;

    /** 扁平 item 列表：message / function_call / function_call_output / reasoning */
    private List<ResponsesItem> input;

    private Boolean stream;

    /** 恒为 false（无状态）；用户可用 customFields 覆盖 */
    private Boolean store;

    /** 输出上限。与 Anthropic 适配器不同，这里 null 就让它省略，不塞人为默认值 */
    private Integer max_output_tokens;

    private Double temperature;

    private Double top_p;

    /** 思考档位；thinking 关或用户没配时为 null，整个字段省略 */
    private Reasoning reasoning;

    /** 需要服务端额外下发的字段，如 {@code reasoning.encrypted_content} */
    private List<String> include;

    /** 扁平工具声明；无工具时为 null（空数组会被严格的服务端拒） */
    private List<ResponsesToolRegister> tools;

    /**
     * 适配器自己会写入的顶层 key，同名自定义字段一律丢弃（内置优先）——直接走 @JsonAnyGetter
     * 展开会和这些属性撞出重复 key。reasoning 虽由适配器写入，但支持被 customFields 覆盖，
     * 覆盖逻辑在适配器里，不走这里。
     */
    private static final Set<String> RESERVED_BODY_KEYS = Set.of(
            "model", "instructions", "input", "stream", "store",
            "max_output_tokens", "temperature", "top_p", "reasoning", "include", "tools");

    /** 厂商自定义字段（模型设置里的 customFields），经 setExtraBody 过滤后展开为请求体顶层字段 */
    @JsonIgnore
    private Map<String, Object> extraBody = new LinkedHashMap<>();

    public ResponsesAIRequest setExtraBody(Map<String, Object> extra) {
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

    public ResponsesAIRequest() {
    }

    /** 思考档位。仅 {@code effort} 一个字段，模型不支持推理时整个对象不出现 */
    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Reasoning {

        /** minimal / low / medium / high */
        private String effort;

        public Reasoning() {
        }
    }
}
