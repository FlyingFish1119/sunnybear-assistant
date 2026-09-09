package com.fishsunny.assistant.engine.tts;

/*
 * @Usage SiliconFlow TTS 客户端（OpenAI 兼容 /v1/audio/speech 形状）。
 *       单一服务商，直接具体实现，不做接口/工厂抽象——真需要第二家再抽。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/10 00:05
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.utils.EnvResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求体：{model, input, voice, response_format}
 * 响应体：音频二进制（非 JSON）
 */
@Slf4j
@Component
public class TTSClient {

    /**
     * -- GETTER --
     * 暴露配置（断句参数等由 TTSSpeaker 按需读取），本类自身只做请求发送/接收
     */
    @Getter
    private final TTSSettings settings;
    private final HttpClient httpClient;

    /**
     * ObjectMapper 必须走构造器注入而非 ObjectMapperFactory 静态工厂：本类是 Spring bean，
     * 启动期构造可能早于 ObjectMapperFactory 完成静态赋值（该静态值只适合反射创建的 adapter 用）
     */
    private final ObjectMapper objectMapper;

    public TTSClient(TTSSettings settings, @Qualifier("aiHttpClient") HttpClient httpClient,
                     ObjectMapper objectMapper) {
        this.settings = settings;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步合成一段文本，阻塞直到音频返回。只做"请求发送/接收"：
     * 断句/节流等职责在调用方（TTSSpeaker / adapter）。
     *
     * @return 音频原始字节（封装格式见 settings.format，默认 mp3）
     */
    public byte[] synthesize(String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.getModel());
        body.put("input", text);
        body.put("voice", settings.getVoice());
        body.put("response_format", settings.getFormat());
        body.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + EnvResolver.resolve(settings.getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String error = new String(response.body(), StandardCharsets.UTF_8);
            if (error.length() > 500) {
                error = error.substring(0, 500) + "...(truncated)";
            }
            log.warn("TTS synth failed, status: {}, body: {}", response.statusCode(), error);
            throw new RuntimeException("TTS synth failed: HTTP " + response.statusCode());
        }
        return response.body();
    }
}
