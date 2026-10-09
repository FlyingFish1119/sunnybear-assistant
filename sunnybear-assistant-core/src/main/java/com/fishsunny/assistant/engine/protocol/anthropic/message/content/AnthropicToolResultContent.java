package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicToolResultContent extends AnthropicContentBlock {

    private final String type = "tool_result";

    private String tool_use_id;

    /**
     * 工具结果内容。纯文本时是 String；带图片等附件时是 content block 数组
     * （Anthropic 允许 tool_result.content 为数组，这是工具产出图片回传给 Claude 的唯一通道）。
     */
    private Object content;

    public AnthropicToolResultContent() {
    }

    public AnthropicToolResultContent(String toolUseId, Object content) {
        this.tool_use_id = toolUseId;
        this.content = content;
    }
}
