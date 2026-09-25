package com.fishsunny.assistant.engine.protocol.standard.content.video;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/*
 * @Usage OpenAI 兼容的视频地址块：{"url": "data:video/mp4;base64,..."}。
 *        与 StandardImageUrl 同构，供支持 video_url 的端点（如 Kimi）使用。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardVideoUrl {

    private String url;
}
