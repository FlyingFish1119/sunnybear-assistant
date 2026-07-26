package com.fishsunny.assistant.plug.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** view 命令返回（仅含 core 需要的 base64 字段） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ViewImageResult {
    private String base64;
}
