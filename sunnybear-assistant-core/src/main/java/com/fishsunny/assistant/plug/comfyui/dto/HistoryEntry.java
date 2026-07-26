package com.fishsunny.assistant.plug.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 生成任务结果（仅含 core 需要的字段） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEntry {
    private Map<String, NodeOutput> outputs;

    /** 遍历 outputs 提取所有图片文件名 */
    public List<String> collectFilenames() {
        List<String> filenames = new ArrayList<>();
        if (outputs == null) return filenames;
        for (NodeOutput output : outputs.values()) {
            if (output.getImages() != null) {
                for (ImageRef img : output.getImages()) {
                    if (img.getFilename() != null && !img.getFilename().isEmpty()) {
                        filenames.add(img.getFilename());
                    }
                }
            }
        }
        return filenames;
    }
}
