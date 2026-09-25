package com.fishsunny.assistant.engine.protocol.standard.content.video;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import lombok.Data;

/*
 * @Usage OpenAI 兼容的视频内容块：{"type":"video_url","video_url":{"url":"..."}}。
 *        OpenAI 官方 Chat Completions 不收；Kimi（kimi-k2.6 / kimi-k3）等扩展了
 *        video_url 的端点可接收。不支持的端点会在请求时报错，由调用方知晓。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardVideoContent implements StandardContent {

    private final String type = "video_url";

    private StandardVideoUrl video_url;

    public StandardVideoContent(String url) {
        StandardVideoUrl standardVideoUrl = new StandardVideoUrl();
        standardVideoUrl.setUrl(url);
        this.video_url = standardVideoUrl;
    }

    public StandardVideoContent() {
    }
}
