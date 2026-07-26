package com.fishsunny.assistant.plug.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** 输出节点（仅含 core 需要的字段） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeOutput {
    private List<ImageRef> images;
}
