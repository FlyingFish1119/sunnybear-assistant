package com.fishsunny.assistant.engine.adapter.standard.multimodal;

/*
 * @Usage 多模态适配器报文快照：打印 convertToTarget 之后的最终请求 JSON，
 *        并用 Kimi 风格的流式报文回放 response 侧，确认 reasoning_content 能否走通。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.StandardAIResponse;
import com.fishsunny.assistant.engine.protocol.standard.StandardStreamAIResponse;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.MultimodalAIRequest;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterFunction;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterParameter;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterProperty;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 只做打印，不做断言——目的是肉眼比对发给 Kimi 的报文体。
 */
class MultimodalAdapterJsonDumpTest {

    /** 与 Spring Boot 默认配置一致：未知字段不抛错 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String BASE_URL = "https://api.moonshot.cn/v1/chat/completions";

    /** Kimi 报文里的图片用极短 base64，避免刷屏 */
    private static final String TINY_IMAGE_DATA_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";

    // ------------------------------------------------------------------
    // 1. 请求侧：dump 最终 JSON
    // ------------------------------------------------------------------

    @Test
    void dumpNonStreamRequest_thinkingOn() throws Exception {
        dump("非流式 / thinking=true", new MultimodalAIAdapter(option(StandardAIResponse.class)),
                buildRequest(true));
    }

    @Test
    void dumpNonStreamRequest_thinkingOff() throws Exception {
        dump("非流式 / thinking=false", new MultimodalAIAdapter(option(StandardAIResponse.class)),
                buildRequest(false));
    }

    @Test
    void dumpStreamRequest_thinkingOn() throws Exception {
        dump("流式 / thinking=true", new MultimodalStreamAIAdapter(option(StandardStreamAIResponse.class)),
                buildRequest(true));
    }

    @Test
    void dumpStreamRequest_thinkingOff() throws Exception {
        dump("流式 / thinking=false", new MultimodalStreamAIAdapter(option(StandardStreamAIResponse.class)),
                buildRequest(false));
    }

    // ------------------------------------------------------------------
    // 2. 响应侧：回放 Kimi 流式报文，看 reasoning_content 有没有被吃进去
    // ------------------------------------------------------------------

    @Test
    void kimiStreamDeltaRoundTrip() throws Exception {
        MultimodalStreamAIAdapter adapter = new MultimodalStreamAIAdapter(option(StandardStreamAIResponse.class));

        // Kimi 思考模型的 delta：reasoning_content 先来，content 后到，字段与 content 同级
        List<String> deltas = List.of(
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1757000000,\"model\":\"kimi-k2.6\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"用户问我\"}}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1757000000,\"model\":\"kimi-k2.6\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\",\"reasoning_content\":\"几点了，我得查一下。\"}}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1757000000,\"model\":\"kimi-k2.6\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"现在是下午三点\"}}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1757000000,\"model\":\"kimi-k2.6\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
        );

        System.out.println("\n===== 流式响应回放：Kimi delta =====");
        for (String line : deltas) {
            StandardStreamAIResponse response = MAPPER.readValue(line, StandardStreamAIResponse.class);
            // 先看反序列化结果：reasoning_content 有没有落到 message 上
            System.out.println("[raw line] " + line);
            System.out.println("[解析后]   " + MAPPER.writeValueAsString(response.getChoices()[0].getDelta()));

            adapter.collectChunk(response);
            ChatResponse converted = (ChatResponse) adapter.convertToMaster(response);
            System.out.println("[转 master]" + MAPPER.writeValueAsString(converted));
            System.out.println("[finished] " + adapter.finished(response));
        }
        System.out.println("[累计 reasoning] " + adapter.getReasoning());
        System.out.println("[累计 content]   " + adapter.getContent());
        System.out.println("[工具调用]       " + adapter.getToolCalls());
    }

    /**
     * 字段名试探：只有 reasoning_content 会被接住，别名（reasoning / thinking / cot）
     * 属于未知字段，Spring Boot 默认的 ObjectMapper 静默忽略 → CoT 凭空消失。
     */
    @Test
    void reasoningFieldNameVariants() throws Exception {
        String[] lines = {
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"A: reasoning_content\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"B: reasoning\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"thinking\":\"C: thinking\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_details\":[{\"text\":\"D: reasoning_details\"}]}}]}"
        };
        System.out.println("\n===== 字段名试探（哪个能被接住） =====");
        for (String line : lines) {
            StandardStreamAIResponse response = MAPPER.readValue(line, StandardStreamAIResponse.class);
            System.out.println("[输入]   " + line);
            System.out.println("[解析后] " + MAPPER.writeValueAsString(response.getChoices()[0].getDelta()));
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private AIAdapterOption option(Class<? extends com.fishsunny.assistant.engine.protocol.AIResponse> targetRespCls) {
        return new AIAdapterOption(BASE_URL, "sk-test",
                ChatRequest.class, MultimodalAIRequest.class, ChatResponse.class, targetRespCls);
    }

    private void dump(String title, AIAdapter adapter, ChatRequest request) throws Exception {
        AIRequest target = adapter.convertToTarget(request);
        System.out.println("\n===== " + title + " : " + target.getClass().getSimpleName() + " =====");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(target));
    }

    /**
     * 典型一轮：system + user + assistant(reasoning + tool_call) + 多模态 tool 结果。
     * thinking 对应设置里的"思考"开关。
     */
    private ChatRequest buildRequest(boolean thinking) {
        ChatMessage system = new ChatMessage().system("你是阳阳，一只熊兽人助手。");
        ChatMessage user = new ChatMessage().user("帮我看看屏幕现在是什么样");

        ChatMessage assistant = new ChatMessage()
                .setRole("assistant")
                .setReasoningContent("主人要看屏幕，我先截个图。")
                .setToolCalls(List.of(new ChatToolRequest()
                        .setId("call_screenshot_1").setName("screen_shot").setArguments("{\"display\":0}")));

        ChatMessage tool = new ChatMessage()
                .setRole("tool")
                .setToolCallId("call_screenshot_1")
                .setContents(List.of(
                        new TextContent("截图完成，尺寸 1920x1080"),
                        new ImageContent(TINY_IMAGE_DATA_URI)));

        ChatSettings settings = new ChatSettings()
                .setModel("kimi-k2.6")
                .setThinking(thinking)
                .setStream(true)
                .setMax_tokens(32768)
                .setCustomFields(new HashMap<>());

        return new ChatRequest()
                .setMessages(List.of(system, user, assistant, tool))
                .setSettings(settings)
                .setTools(List.of(screenShotTool()));
    }

    private StandardToolRegister screenShotTool() {
        Map<String, StandardToolRegisterProperty> properties = new HashMap<>();
        properties.put("display", new StandardToolRegisterProperty().setType("integer").setDescription("显示器序号"));
        return new StandardToolRegister().setFunction(new StandardToolRegisterFunction()
                .setName("screen_shot")
                .setDescription("截取当前屏幕")
                .setParameters(new StandardToolRegisterParameter().setProperties(properties)));
    }
}
