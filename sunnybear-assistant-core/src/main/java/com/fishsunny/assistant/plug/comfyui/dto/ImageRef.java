package com.fishsunny.assistant.plug.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** ComfyUI 生成的单个图片引用（仅含 core 需要的字段） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageRef {
    private String filename;
}
