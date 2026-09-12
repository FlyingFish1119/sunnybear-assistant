package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * message 的 content 分片与 reasoning 的 summary 分片共用的扁平分片。
 *
 * <p>两处分片的字段是子集关系，所以一个类装得下：
 * <ul>
 *   <li>message content：{@code {"type":"input_text","text":...}}、
 *       {@code {"type":"input_image","image_url":...}}、
 *       {@code {"type":"output_text","text":...}}</li>
 *   <li>reasoning summary：{@code {"type":"summary_text","text":...}}</li>
 * </ul>
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesContentPart {

    private String type;

    private String text;

    /** type=input_image 时的图片地址，data URI 与 https URL 都收 */
    private String image_url;

    /** type=input_image 时的清晰度档位（low / high / auto），不设由服务端决定 */
    private String detail;

    public ResponsesContentPart() {
    }

    public static ResponsesContentPart inputText(String text) {
        return new ResponsesContentPart().setType(TYPE_INPUT_TEXT).setText(text);
    }

    public static ResponsesContentPart outputText(String text) {
        return new ResponsesContentPart().setType(TYPE_OUTPUT_TEXT).setText(text);
    }

    public static ResponsesContentPart inputImage(String url) {
        return new ResponsesContentPart().setType(TYPE_INPUT_IMAGE).setImage_url(url);
    }

    public static ResponsesContentPart summaryText(String text) {
        return new ResponsesContentPart().setType(TYPE_SUMMARY_TEXT).setText(text);
    }

    public static final String TYPE_INPUT_TEXT = "input_text";
    public static final String TYPE_OUTPUT_TEXT = "output_text";
    public static final String TYPE_INPUT_IMAGE = "input_image";
    public static final String TYPE_SUMMARY_TEXT = "summary_text";
}
