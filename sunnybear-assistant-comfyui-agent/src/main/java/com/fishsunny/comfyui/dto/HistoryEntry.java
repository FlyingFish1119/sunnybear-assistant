package com.fishsunny.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GET /history/{prompt_id} 的单个任务结果 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEntry {
    private Map<String, NodeOutput> outputs;

    /** 遍历所有 outputs 收集图片文件名 */
    public List<String> collectImageFilenames() {
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
