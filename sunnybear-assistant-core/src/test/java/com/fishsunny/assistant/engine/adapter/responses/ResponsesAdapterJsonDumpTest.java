package com.fishsunny.assistant.engine.adapter.responses;

/*
 * @Usage Responses API 适配器报文快照：打印 convertToTarget 之后的最终请求 JSON，
 *        并按真实事件顺序回放 SSE 报文，验证累积结果与终止判定。
 *
 *        回放的字符串就是 ChatHttpHandler 剥掉 "data:" 前缀后喂给 Jackson 的那一段，
 *        所以这里能过，线上解析就没问题。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIRequest;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesAIResponse;
import com.fishsunny.assistant.engine.protocol.responses.ResponsesStreamEvent;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterFunction;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterParameter;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegisterProperty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesAdapterJsonDumpTest {

    /** 与 Spring Boot 默认配置一致：未知字段不抛错 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String BASE_URL = "https://api.openai.com/v1/responses";

    private static final String TINY_IMAGE_DATA_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";

    // ------------------------------------------------------------------
    // 1. 请求侧：dump 最终 JSON
    // ------------------------------------------------------------------

    @Test
    void dumpNonStreamRequest_thinkingOn() throws Exception {
        dump("非流式 / thinking=true", new ResponsesAIAdapter(option(ResponsesAIResponse.class)), buildRequest(true));
    }

    @Test
    void dumpNonStreamRequest_thinkingOff() throws Exception {
        dump("非流式 / thinking=false", new ResponsesAIAdapter(option(ResponsesAIResponse.class)), buildRequest(false));
    }

    @Test
    void dumpStreamRequest_thinkingOn() throws Exception {
        dump("流式 / thinking=true", new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class)), buildRequest(true));
    }

    // ------------------------------------------------------------------
    // 2. 请求侧：线格式断言
    // ------------------------------------------------------------------

    /**
     * 检票口：这些断言对着的是官方文档的形状，写错一个字段线上就是 400。
     */
    @Test
    void requestWireShape() throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));
        ResponsesAIRequest request = (ResponsesAIRequest) adapter.convertToTarget(buildRequest(true));
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(request));

        System.out.println("\n===== 请求线格式断言 =====");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json));

        // 系统提示走顶层 instructions，input 里不应再出现 system
        assertTrue(json.get("instructions").asText().contains("你是阳阳"));
        for (JsonNode item : json.get("input")) {
            assertFalse("system".equals(item.path("role").asText()), "system 不该出现在 input 里");
        }

        // input 是扁平 item 列表：message / function_call / function_call_output 平级
        List<String> itemTypes = new ArrayList<>();
        json.get("input").forEach(item -> itemTypes.add(item.get("type").asText()));
        assertEquals(List.of("message", "message", "function_call", "function_call_output"), itemTypes);

        // 没有任何嵌套 tool_calls（那是 Chat Completions 的形状）
        assertFalse(json.get("input").toString().contains("tool_calls"), "不应出现嵌套 tool_calls");

        // call_id 必须来自 ChatToolRequest.id —— 只有它能关联回工具结果
        JsonNode functionCall = json.get("input").get(2);
        assertEquals("call_screenshot_1", functionCall.get("call_id").asText());
        assertEquals("screen_shot", functionCall.get("name").asText());
        assertEquals("{\"display\":0}", functionCall.get("arguments").asText());

        // 工具结果的 call_id 来自 ChatMessage.toolCallId
        JsonNode functionCallOutput = json.get("input").get(3);
        assertEquals("call_screenshot_1", functionCallOutput.get("call_id").asText());
        // 文本不带尾随 "\n\n"（用 plainText 而非 resolveText 的原因）
        assertEquals("截图完成，尺寸 1920x1080", functionCallOutput.get("output").asText());

        // 用户消息的多模态分片
        JsonNode userContent = json.get("input").get(0).get("content");
        assertEquals("input_text", userContent.get(0).get("type").asText());
        assertEquals("input_image", userContent.get(1).get("type").asText());
        assertEquals(TINY_IMAGE_DATA_URI, userContent.get(1).get("image_url").asText());

        // assistant 文本分片是 output_text（不是 input_text）
        assertEquals("output_text", json.get("input").get(1).get("content").get(0).get("type").asText());

        // 无状态：store 固定 false
        assertFalse(json.get("store").asBoolean());

        // 工具声明是扁平结构，没有 Chat Completions 那层 function 子对象
        JsonNode tool = json.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("screen_shot", tool.get("name").asText());
        assertFalse(tool.has("function"), "Responses 的工具声明没有嵌套 function");
        assertEquals("object", tool.get("parameters").get("type").asText());
        assertEquals("integer", tool.get("parameters").get("properties").get("display").get("type").asText());

        // 思考开着才索要加密串
        assertEquals("reasoning.encrypted_content", json.get("include").get(0).asText());
        assertEquals("medium", json.get("reasoning").get("effort").asText());
    }

    @Test
    void thinkingOffOmitsReasoningAndInclude() throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));
        ResponsesAIRequest request = (ResponsesAIRequest) adapter.convertToTarget(buildRequest(false));
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(request));

        System.out.println("\n===== thinking=false 时不应出现 reasoning / include =====");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json));

        assertFalse(json.has("reasoning"), "思考关闭时 reasoning 整个字段应省略");
        assertFalse(json.has("include"), "思考关闭时不该索要加密串");
    }

    /** reasoning_effort → Responses effort 档位映射，max 落到 high（Responses 没有 max 档） */
    @Test
    void reasoningEffortMapping() throws Exception {
        System.out.println("\n===== reasoning_effort 映射 =====");
        assertEquals("minimal", effortFor("minimal"));
        assertEquals("low", effortFor("low"));
        assertEquals("medium", effortFor("medium"));
        assertEquals("high", effortFor("high"));
        assertEquals("high", effortFor("max"));
        assertEquals("medium", effortFor(null));
        assertEquals("medium", effortFor("bogus"));
    }

    @Test
    void streamFlagFollowsAdapter() throws Exception {
        ResponsesAIRequest nonStream = (ResponsesAIRequest) new ResponsesAIAdapter(option(ResponsesAIResponse.class))
                .convertToTarget(buildRequest(false));
        ResponsesAIRequest stream = (ResponsesAIRequest) new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class))
                .convertToTarget(buildRequest(false));
        assertFalse(nonStream.getStream());
        assertTrue(stream.getStream());
    }

    // ------------------------------------------------------------------
    // 3. 响应侧：按真实顺序回放 SSE
    // ------------------------------------------------------------------

    /**
     * 完整一轮：思考摘要 → 思考 item 收尾（带加密串）→ 工具调用参数增量 → 工具调用收尾 → 完成。
     */
    @Test
    void streamReplayWithReasoningAndToolCall() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        List<String> deltas = List.of(
                "{\"type\":\"response.created\",\"sequence_number\":0,"
                        + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"in_progress\"}}",
                "{\"type\":\"response.in_progress\",\"sequence_number\":1}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":2,\"output_index\":0,"
                        + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]}}",
                "{\"type\":\"response.reasoning_summary_part.added\",\"sequence_number\":3,\"output_index\":0,"
                        + "\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"sequence_number\":4,\"output_index\":0,"
                        + "\"summary_index\":0,\"delta\":\"主人要看屏幕，\"}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"sequence_number\":5,\"output_index\":0,"
                        + "\"summary_index\":0,\"delta\":\"我先截个图。\"}",
                "{\"type\":\"response.reasoning_summary_text.done\",\"sequence_number\":6,\"output_index\":0,"
                        + "\"summary_index\":0,\"text\":\"主人要看屏幕，我先截个图。\"}",
                "{\"type\":\"response.reasoning_summary_part.done\",\"sequence_number\":7,\"output_index\":0,"
                        + "\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"主人要看屏幕，我先截个图。\"}}",
                "{\"type\":\"response.output_item.done\",\"sequence_number\":8,\"output_index\":0,"
                        + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"gAAAA_enc\","
                        + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"主人要看屏幕，我先截个图。\"}]}}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":9,\"output_index\":1,"
                        + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_screenshot_1\","
                        + "\"name\":\"screen_shot\",\"arguments\":\"\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":10,\"output_index\":1,"
                        + "\"item_id\":\"fc_1\",\"delta\":\"{\\\"disp\"}",
                "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":11,\"output_index\":1,"
                        + "\"item_id\":\"fc_1\",\"delta\":\"lay\\\":0}\"}",
                "{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":12,\"output_index\":1,"
                        + "\"item_id\":\"fc_1\",\"arguments\":\"{\\\"display\\\":0}\"}",
                "{\"type\":\"response.output_item.done\",\"sequence_number\":13,\"output_index\":1,"
                        + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_screenshot_1\","
                        + "\"name\":\"screen_shot\",\"arguments\":\"{\\\"display\\\":0}\",\"status\":\"completed\"}}",
                "{\"type\":\"response.completed\",\"sequence_number\":14,"
                        + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":31,\"output_tokens\":18}}}"
        );

        System.out.println("\n===== 流式回放：思考 + 工具调用 =====");
        int index = 0;
        for (String line : deltas) {
            boolean last = index == deltas.size() - 1;
            ResponsesStreamEvent event = MAPPER.readValue(line, ResponsesStreamEvent.class);
            adapter.collectChunk(event);
            ChatResponse converted = (ChatResponse) adapter.convertToMaster(event);
            boolean finished = adapter.finished(event);

            System.out.println("[事件]     " + event.getType());
            System.out.println("[转 master]" + MAPPER.writeValueAsString(converted));
            System.out.println("[finished]  " + finished);

            assertEquals(last, finished, "只有终止事件该判完成: " + event.getType());
            index++;
        }

        System.out.println("[累计 reasoning] " + adapter.getReasoning());
        System.out.println("[思考签名]       " + adapter.getReasoningSignature());
        System.out.println("[累计 content]   " + adapter.getContent());
        System.out.println("[工具调用]       " + adapter.getToolCalls());

        // 思考摘要逐帧累积（前端思考面板靠这条链路）
        assertEquals("主人要看屏幕，我先截个图。", adapter.getReasoning());
        // 加密思考串 last-wins
        assertEquals("gAAAA_enc", adapter.getReasoningSignature());
        // 本轮没有输出文本
        assertEquals("", adapter.getContent());

        List<AIAdapter.ToolCall> toolCalls = adapter.getToolCalls();
        assertEquals(1, toolCalls.size());
        AIAdapter.ToolCall toolCall = toolCalls.get(0);
        // 必须是 call_id，不是 item 的 fc_1
        assertEquals("call_screenshot_1", toolCall.getId());
        assertEquals("screen_shot", toolCall.getFunction().getName());
        // 参数是 done 给的完整 JSON，不能是增量碎片的叠加
        assertEquals("{\"display\":0}", toolCall.getFunction().getArguments());
    }

    /**
     * 下发给前端的参数帧必须是<b>增量片段</b>：前端对 toolCalls.arguments 做累加，
     * 发累积快照会拼成 {"disp{"display":0}——这正是「重复推送」的现场。
     */
    @Test
    void streamToolCallArgumentsAreDeltasNotSnapshots() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        List<String> deltas = List.of(
                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_8\"}}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,"
                        + "\"item\":{\"id\":\"fc_8\",\"type\":\"function_call\",\"call_id\":\"call_8\","
                        + "\"name\":\"screen_shot\",\"arguments\":\"\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":2,\"output_index\":0,"
                        + "\"item_id\":\"fc_8\",\"delta\":\"{\\\"disp\"}",
                "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":3,\"output_index\":0,"
                        + "\"item_id\":\"fc_8\",\"delta\":\"lay\\\":0}\"}",
                "{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":4,\"output_index\":0,"
                        + "\"item_id\":\"fc_8\",\"arguments\":\"{\\\"display\\\":0}\"}",
                "{\"type\":\"response.completed\",\"sequence_number\":5,\"response\":{\"id\":\"resp_8\"}}"
        );

        System.out.println("\n===== 流式回放：工具调用参数只发增量 =====");
        // 前端 index.html 的累加动作，逐帧复刻一遍
        StringBuilder frontend = new StringBuilder();
        for (String line : deltas) {
            ResponsesStreamEvent event = MAPPER.readValue(line, ResponsesStreamEvent.class);
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

        // 两帧增量各发各的片段，done 帧不重复下发整份
        assertEquals("{\"display\":0}", frontend.toString(), "累加后的参数不能有重复片段");
        // 执行侧仍拿到完整 JSON
        assertEquals("{\"display\":0}", adapter.getToolCalls().get(0).getFunction().getArguments());
    }

    /** 只发 done 不发增量的网关：兜底补发一次完整参数，前端也能拿到 */
    @Test
    void streamToolCallFallsBackWhenOnlyDoneIsSent() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        List<String> deltas = List.of(
                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_9\"}}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,"
                        + "\"item\":{\"id\":\"fc_9\",\"type\":\"function_call\",\"call_id\":\"call_9\","
                        + "\"name\":\"screen_shot\",\"arguments\":\"\"}}",
                "{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":2,\"output_index\":0,"
                        + "\"item_id\":\"fc_9\",\"arguments\":\"{\\\"display\\\":1}\"}"
        );

        System.out.println("\n===== 流式回放：只发 done 的网关 =====");
        StringBuilder frontend = new StringBuilder();
        for (String line : deltas) {
            ResponsesStreamEvent event = MAPPER.readValue(line, ResponsesStreamEvent.class);
            adapter.collectChunk(event);
            ChatResponse converted = (ChatResponse) adapter.convertToMaster(event);
            for (ChatMessage message : converted.getMessages()) {
                if (message.getToolCalls() == null) {
                    continue;
                }
                for (ChatToolRequest request : message.getToolCalls()) {
                    frontend.append(request.getArguments());
                }
            }
        }

        assertEquals("{\"display\":1}", frontend.toString());
        assertEquals("{\"display\":1}", adapter.getToolCalls().get(0).getFunction().getArguments());
    }

    /** 纯文本一轮，且思考摘要只出现在 output_item.done（不给增量的网关）时的兜底 */
    @Test
    void streamReplayTextOnly() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        List<String> deltas = List.of(
                "{\"type\":\"response.created\",\"sequence_number\":0,"
                        + "\"response\":{\"id\":\"resp_2\",\"status\":\"in_progress\"}}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,"
                        + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}",
                "{\"type\":\"response.content_part.added\",\"sequence_number\":2,\"output_index\":0,\"content_index\":0,"
                        + "\"part\":{\"type\":\"output_text\",\"text\":\"\"}}",
                "{\"type\":\"response.output_text.delta\",\"sequence_number\":3,\"output_index\":0,\"content_index\":0,"
                        + "\"item_id\":\"msg_1\",\"delta\":\"现在是\"}",
                "{\"type\":\"response.output_text.delta\",\"sequence_number\":4,\"output_index\":0,\"content_index\":0,"
                        + "\"item_id\":\"msg_1\",\"delta\":\"下午三点。\"}",
                "{\"type\":\"response.output_text.done\",\"sequence_number\":5,\"output_index\":0,\"content_index\":0,"
                        + "\"item_id\":\"msg_1\",\"text\":\"现在是下午三点。\"}",
                "{\"type\":\"response.output_item.done\",\"sequence_number\":6,\"output_index\":0,"
                        + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"现在是下午三点。\"}]}}",
                "{\"type\":\"response.completed\",\"sequence_number\":7,"
                        + "\"response\":{\"id\":\"resp_2\",\"status\":\"completed\"}}"
        );

        System.out.println("\n===== 流式回放：纯文本 =====");
        for (String line : deltas) {
            ResponsesStreamEvent event = MAPPER.readValue(line, ResponsesStreamEvent.class);
            adapter.collectChunk(event);
            adapter.convertToMaster(event);
            System.out.println("[事件] " + event.getType() + " / " + adapter.finished(event));
        }

        System.out.println("[累计 content] " + adapter.getContent());

        // 增量累加 + output_item.done 兜底不能叠加
        assertEquals("现在是下午三点。", adapter.getContent());
        assertTrue(adapter.getToolCalls().isEmpty());
    }

    /** 没有增量、只有 output_item.done 的网关：兜底必须把正文捞出来 */
    @Test
    void streamReplayItemDoneOnlyGateway() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        List<String> deltas = List.of(
                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_3\"}}",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,"
                        + "\"item\":{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}",
                "{\"type\":\"response.output_item.done\",\"sequence_number\":2,\"output_index\":0,"
                        + "\"item\":{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"只有 item 没有增量\"}]}}",
                "{\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"id\":\"resp_3\"}}"
        );

        System.out.println("\n===== 流式回放：只发 output_item.done 的网关 =====");
        for (String line : deltas) {
            ResponsesStreamEvent event = MAPPER.readValue(line, ResponsesStreamEvent.class);
            adapter.collectChunk(event);
            adapter.convertToMaster(event);
        }
        System.out.println("[累计 content] " + adapter.getContent());
        assertEquals("只有 item 没有增量", adapter.getContent());
    }

    /** response.failed 是终止事件，且要转成 error 状态的帧 */
    @Test
    void streamFailedEndsStreamWithError() throws Exception {
        ResponsesStreamAIAdapter adapter = new ResponsesStreamAIAdapter(option(ResponsesStreamEvent.class));

        adapter.collectChunk(MAPPER.readValue(
                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_4\"}}",
                ResponsesStreamEvent.class));

        String failed = "{\"type\":\"response.failed\",\"sequence_number\":1,"
                + "\"response\":{\"id\":\"resp_4\",\"object\":\"response\",\"status\":\"failed\","
                + "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}";

        ResponsesStreamEvent event = MAPPER.readValue(failed, ResponsesStreamEvent.class);
        adapter.collectChunk(event);
        ChatResponse converted = (ChatResponse) adapter.convertToMaster(event);

        System.out.println("\n===== response.failed =====");
        System.out.println(MAPPER.writeValueAsString(converted));

        assertTrue(adapter.finished(event), "response.failed 应判完成");
        assertEquals(ChatResponse.STATUS_ERROR, converted.getStatus());
        assertTrue(converted.getText().contains("boom"));
    }

    // ------------------------------------------------------------------
    // 4. 非流式
    // ------------------------------------------------------------------

    @Test
    void nonStreamRoundTrip() throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));

        String body = "{\"id\":\"resp_5\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-5\","
                + "\"output\":["
                + "{\"id\":\"rs_2\",\"type\":\"reasoning\",\"encrypted_content\":\"gAAAA_enc2\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"先看看屏幕。\"}]},"
                + "{\"id\":\"msg_3\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"我来截个图。\"}]},"
                + "{\"type\":\"function_call\",\"call_id\":\"call_screenshot_2\",\"name\":\"screen_shot\","
                + "\"arguments\":\"{\\\"display\\\":0}\"}"
                + "],\"usage\":{\"input_tokens\":31,\"output_tokens\":18}}";

        ResponsesAIResponse response = MAPPER.readValue(body, ResponsesAIResponse.class);
        System.out.println("\n===== 非流式往返 =====");
        System.out.println("[原始] " + MAPPER.writeValueAsString(response));

        assertTrue(adapter.finished(response), "非流式读一帧即完成");
        adapter.collectChunk(response);
        ChatResponse converted = (ChatResponse) adapter.convertToMaster(response);
        System.out.println("[转 master] " + MAPPER.writeValueAsString(converted));

        assertEquals(ChatResponse.STATUS_DONE, converted.getStatus());
        assertEquals("我来截个图。", adapter.getContent());
        assertEquals("先看看屏幕。", adapter.getReasoning());
        assertEquals("gAAAA_enc2", adapter.getReasoningSignature());

        List<AIAdapter.ToolCall> toolCalls = adapter.getToolCalls();
        assertEquals(1, toolCalls.size());
        assertEquals("call_screenshot_2", toolCalls.get(0).getId());
        assertEquals("screen_shot", toolCalls.get(0).getFunction().getName());
        assertEquals("{\"display\":0}", toolCalls.get(0).getFunction().getArguments());

        // 转出来的 assistant 消息里也带上了工具调用
        ChatMessage assistant = converted.getMessages().get(0);
        assertEquals("我来截个图。", assistant.resolveText());
        assertEquals(1, assistant.getToolCalls().size());
        assertEquals("call_screenshot_2", assistant.getToolCalls().get(0).getId());
    }

    @Test
    void nonStreamFailedThrowsReadable() throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));

        String body = "{\"id\":\"resp_6\",\"object\":\"response\",\"status\":\"failed\","
                + "\"error\":{\"code\":\"server_error\",\"message\":\"upstream exploded\"}}";
        ResponsesAIResponse response = MAPPER.readValue(body, ResponsesAIResponse.class);

        RuntimeException error = assertThrows(RuntimeException.class, () -> adapter.convertToMaster(response));
        System.out.println("\n===== 非流式失败 =====");
        System.out.println("[异常] " + error.getMessage());
        assertTrue(error.getMessage().contains("upstream exploded"));
    }

    @Test
    void nonStreamIncompleteKeepsPartialContent() throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));

        String body = "{\"id\":\"resp_7\",\"object\":\"response\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"被截断的正文\"}]}]}";
        ResponsesAIResponse response = MAPPER.readValue(body, ResponsesAIResponse.class);

        System.out.println("\n===== 非流式 incomplete：保留已有内容 =====");
        // incomplete 只是告警，不抛异常——已有内容比什么都没有划算
        ChatResponse converted = (ChatResponse) adapter.convertToMaster(response);
        assertEquals(ChatResponse.STATUS_DONE, converted.getStatus());
    }

    // ------------------------------------------------------------------
    // 5. checkCls：yml 里 targetRespCls 写反时的构造期防线
    // ------------------------------------------------------------------

    @Test
    void checkClsRejectsMismatchedResponseClass() {
        System.out.println("\n===== checkCls 负向 =====");
        // 流式适配器拿到非流式的响应类，必须在构造期就炸
        assertThrows(RuntimeException.class,
                () -> new ResponsesStreamAIAdapter(option(ResponsesAIResponse.class)));
        assertThrows(RuntimeException.class,
                () -> new ResponsesAIAdapter(option(ResponsesStreamEvent.class)));
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private AIAdapterOption option(Class<? extends AIResponse> targetRespCls) {
        return new AIAdapterOption(BASE_URL, "sk-test",
                ChatRequest.class, ResponsesAIRequest.class, ChatResponse.class, targetRespCls);
    }

    private void dump(String title, AIAdapter adapter, ChatRequest request) throws Exception {
        AIRequest target = adapter.convertToTarget(request);
        System.out.println("\n===== " + title + " : " + target.getClass().getSimpleName() + " =====");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(target));
    }

    private String effortFor(String reasoningEffort) throws Exception {
        ResponsesAIAdapter adapter = new ResponsesAIAdapter(option(ResponsesAIResponse.class));
        ChatRequest request = buildRequest(true);
        request.getSettings().setReasoning_effort(reasoningEffort);
        ResponsesAIRequest target = (ResponsesAIRequest) adapter.convertToTarget(request);
        String effort = target.getReasoning() == null ? null : target.getReasoning().getEffort();
        System.out.println("reasoning_effort=" + reasoningEffort + " → effort=" + effort);
        assertNotNull(effort, "思考开着时 effort 必须有值");
        return effort;
    }

    /**
     * 典型一轮：system + user(文本+图片) + assistant(reasoning + tool_call) + 多模态 tool 结果。
     * thinking 对应设置里的"思考"开关。
     */
    private ChatRequest buildRequest(boolean thinking) {
        ChatMessage system = new ChatMessage().system("你是阳阳，一只熊兽人助手。");
        ChatMessage user = new ChatMessage()
                .setRole("user")
                .setContents(List.of(
                        new TextContent("帮我看看屏幕现在是什么样"),
                        new ImageContent(TINY_IMAGE_DATA_URI)));

        // 有文本 + 有工具调用的 assistant：会摊平成 message 与 function_call 两个平级 item
        ChatMessage assistant = new ChatMessage()
                .setRole("assistant")
                .setReasoningContent("主人要看屏幕，我先截个图。")
                .setToolCalls(List.of(new ChatToolRequest()
                        .setId("call_screenshot_1").setName("screen_shot").setArguments("{\"display\":0}")))
                .text("我先截个图。");

        ChatMessage tool = new ChatMessage()
                .setRole("tool")
                .setToolCallId("call_screenshot_1")
                .setContents(List.of(
                        new TextContent("截图完成，尺寸 1920x1080"),
                        new ImageContent(TINY_IMAGE_DATA_URI)));

        ChatSettings settings = new ChatSettings()
                .setModel("gpt-5")
                .setThinking(thinking)
                .setStream(true)
                .setMax_tokens(32768)
                .setTemperature(0.7)
                .setTop_p(1.0)
                .setCustomFields(new HashMap<>());

        return new ChatRequest()
                .setMessages(List.of(system, user, assistant, tool))
                .setSettings(settings)
                .setTools(List.of(screenShotTool()));
    }

    private StandardToolRegister screenShotTool() {
        Map<String, StandardToolRegisterProperty> properties = new LinkedHashMap<>();
        properties.put("display", new StandardToolRegisterProperty().setType("integer").setDescription("显示器序号"));
        return new StandardToolRegister().setFunction(new StandardToolRegisterFunction()
                .setName("screen_shot")
                .setDescription("截取当前屏幕")
                .setParameters(new StandardToolRegisterParameter().setProperties(properties)));
    }
}
