package com.fishsunny.assistant.engine.adapter.anthropic;

/*
 * @Usage Anthropic 流式适配器报文快照：按真实事件顺序回放 SSE，验证工具调用参数的
 *        下发口径是「本帧 partial_json」而不是「累积快照」。
 *
 *        回放的字符串就是 ChatHttpHandler 剥掉 "data:" 前缀后喂给 Jackson 的那一段。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicAIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.stream.AnthropicStreamResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnthropicStreamAdapterTest {

    /** 与 Spring Boot 默认配置一致：未知字段不抛错 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String BASE_URL = "https://api.anthropic.com/v1/messages";

    /**
     * 下发给前端的分片必须是<b>本帧的 partial_json</b>：前端对 toolCalls.arguments 做累加
     * （index.html 的 `toolCall.arguments += ...`），发累积快照会拼成 {"disp{"display":0}。
     */
    @Test
    void toolCallArgumentsAreDeltasNotSnapshots() throws Exception {
        // 适配器的 objectMapper 来自 Spring 注入的 ObjectMapperFactory；单测没有容器，
        // 这里直接把工厂补上（静态字段），否则解析 delta 会 NPE 被 catch 吞掉
        new ObjectMapperFactory(MAPPER);
        AnthropicStreamAIAdapter adapter =
                new AnthropicStreamAIAdapter(option(AnthropicStreamResponse.class));

        List<String> events = List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\"}}",
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"screen_shot\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"disp\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"lay\\\":0}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
                "{\"type\":\"message_stop\"}"
        );

        System.out.println("\n===== Anthropic 流式回放：工具调用参数只发增量 =====");
        // 前端 index.html 的累加动作，逐帧复刻一遍
        StringBuilder frontend = new StringBuilder();
        for (String line : events) {
            AnthropicStreamResponse event = MAPPER.readValue(line, AnthropicStreamResponse.class);
            adapter.collectChunk(event);
            ChatResponse converted = (ChatResponse) adapter.convertToMaster(event);
            for (ChatMessage message : converted.getMessages()) {
                if (message.getToolCalls() == null) {
                    continue;
                }
                for (ChatToolRequest request : message.getToolCalls()) {
                    frontend.append(request.getArguments());
                    System.out.println("[下发片段] " + request.getArguments());
                }
            }
        }

        // 两帧增量各发各的片段，累加后正好是完整 JSON，没有重复前缀
        assertEquals("{\"display\":0}", frontend.toString(), "累加后的参数不能有重复片段");
        // 执行侧拿到的是同一份完整 JSON
        List<AIAdapter.ToolCall> toolCalls = adapter.getToolCalls();
        assertEquals(1, toolCalls.size());
        assertEquals("toolu_1", toolCalls.get(0).getId());
        assertEquals("screen_shot", toolCalls.get(0).getFunction().getName());
        assertEquals("{\"display\":0}", toolCalls.get(0).getFunction().getArguments());
    }

    private AIAdapterOption option(Class<? extends com.fishsunny.assistant.engine.protocol.AIResponse> targetRespCls) {
        return new AIAdapterOption(BASE_URL, "sk-ant-test",
                ChatRequest.class, AnthropicAIRequest.class, ChatResponse.class, targetRespCls);
    }
}
