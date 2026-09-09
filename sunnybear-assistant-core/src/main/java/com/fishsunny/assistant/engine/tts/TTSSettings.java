package com.fishsunny.assistant.engine.tts;

/*
 * @Usage TTS 配置绑定（application.yml，不进 UI）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/9 23:45
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.tts")
public class TTSSettings {

    /** TTS 总开关；关闭时工厂不向 adapter 注入 TTSClient（HandleTTSAble 全程 no-op），聊天行为与旧版一致 */
    private Boolean enable = false;

    /** TTS 端点完整 URL（SiliconFlow: <a href="https://api.siliconflow.cn/v1/audio/speech">...</a>） */
    private String baseUrl;

    private String apiKey;

    /** TTS 模型（SiliconFlow: FunAudioLLM/CosyVoice2-0.5B） */
    private String model;

    /** 音色（SiliconFlow 形如 "FunAudioLLM/CosyVoice2-0.5B:alex"） */
    private String voice;

    /** 音频封装格式，默认 mp3 */
    private String format = "mp3";
}
