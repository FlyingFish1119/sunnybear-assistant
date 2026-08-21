package com.fishsunny.assistant.engine.protocol.project;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 20:33
 */

import com.fishsunny.assistant.engine.adapter.AIAdapter;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class ChatToolRequest {

    private String id;

    private String name;

    private String arguments;

    public ChatToolRequest() {
    }

    public static List<ChatToolRequest> convert(List<AIAdapter.ToolCall> toolCalls) {
        return toolCalls.stream().map(toolCall -> {
            ChatToolRequest request = new ChatToolRequest();
            request.setId(toolCall.getId());
            request.setName(toolCall.getFunction().getName());
            request.setArguments(toolCall.getFunction().getArguments());
            return request;
        }).toList();
    }
}
