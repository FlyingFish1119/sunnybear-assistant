package com.fishsunny.assistant.engine.protocol.gemini.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工具调用配置。不设置时 Gemini 按 AUTO 处理，适配器默认不发送本对象。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiToolConfig {

    private GeminiFunctionCallingConfig functionCallingConfig;

    public GeminiToolConfig() {
    }

    public static GeminiToolConfig mode(String mode) {
        return new GeminiToolConfig()
                .setFunctionCallingConfig(new GeminiFunctionCallingConfig().setMode(mode));
    }
}
