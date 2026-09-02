package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role;

/*
 * @Usage 多模态 tool 结果消息：content 始终为 content 数组
 *        （纯文本也是 [{type:text,...}]，多模态再加 image_url / input_audio）。
 *        本协议专为支持数组的端点设计，不做字符串回退。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalToolMessage extends MultimodalMessage {

    private final String role = "tool";

    private String tool_call_id;

    /** content 数组：纯文本也是 text part，多模态再加 image_url / input_audio */
    private List<StandardContent> content = new ArrayList<>();

    public MultimodalToolMessage setContent(List<StandardContent> content) {
        this.content = content == null ? new ArrayList<>() : content;
        return this;
    }

    public MultimodalToolMessage() {
    }

    public MultimodalToolMessage(String tool_call_id, List<StandardContent> content) {
        this.tool_call_id = tool_call_id;
        this.content = content == null ? new ArrayList<>() : content;
    }
}
