package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 扁平 item：请求体的 {@code input[]} 与响应体的 {@code output[]} 共用同一个类。
 *
 * <p>Responses API 的 input 不是 role 数组，而是一串平级 item——message / function_call /
 * function_call_output / reasoning 互为兄弟，且 message 自己还带 role。异构轴不是 role，
 * 用多态继承要两级判别器；而这些对象只在请求侧序列化、永不上转型，所以取字段并集的单类做法
 * （与 {@code AnthropicStreamContentBlock} 同一手法）。
 *
 * <p>响应侧的 output item 是请求 item 的超集（多 status / annotations 等），靠
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 容忍。
 *
 * <p>注意 {@code call_id} 与 {@code id} 的区别：只有 {@code call_id}（{@code call_...}）
 * 能把 function_call_output 关联回它对应的 function_call，{@code id}（{@code fc_...}）
 * 是服务端内部的 item 标识。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesItem {

    private String type;

    /** 服务端 item 标识（reasoning item 回传时需要），回传 request 时通常省略 */
    private String id;

    /** type=message 时的 user / assistant / system */
    private String role;

    /** type=message 时的状态，仅响应侧出现 */
    private String status;

    /** type=message 时的内容分片 */
    private List<ResponsesContentPart> content;

    /** type=function_call / function_call_output 时的关联标识 */
    private String call_id;

    /** type=function_call 时的函数名 */
    private String name;

    /** type=function_call 时的参数，JSON 字符串（不是嵌套对象） */
    private String arguments;

    /** type=function_call_output 时的工具结果，纯字符串 */
    private String output;

    /** type=reasoning 时的加密思考内容，用 {@code include:["reasoning.encrypted_content"]} 换取 */
    private String encrypted_content;

    /** type=reasoning 时的思考摘要分片 */
    private List<ResponsesContentPart> summary;

    public ResponsesItem() {
    }

    public static ResponsesItem inputMessage(String role, List<ResponsesContentPart> content) {
        return new ResponsesItem().setType(TYPE_MESSAGE).setRole(role).setContent(content);
    }

    public static ResponsesItem functionCall(String callId, String name, String arguments) {
        return new ResponsesItem()
                .setType(TYPE_FUNCTION_CALL)
                .setCall_id(callId)
                .setName(name)
                .setArguments(arguments);
    }

    public static ResponsesItem functionCallOutput(String callId, String output) {
        return new ResponsesItem()
                .setType(TYPE_FUNCTION_CALL_OUTPUT)
                .setCall_id(callId)
                .setOutput(output);
    }

    public static ResponsesItem reasoning(String id, String encryptedContent, List<ResponsesContentPart> summary) {
        return new ResponsesItem()
                .setType(TYPE_REASONING)
                .setId(id)
                .setEncrypted_content(encryptedContent)
                .setSummary(summary);
    }

    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_FUNCTION_CALL = "function_call";
    public static final String TYPE_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String TYPE_REASONING = "reasoning";
}
