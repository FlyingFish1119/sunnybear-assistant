package com.fishsunny.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueStatus {

    @JsonProperty("queue_running")
    private List<List<Object>> queueRunning;

    @JsonProperty("queue_pending")
    private List<List<Object>> queuePending;

    /** 检查 promptId 是否仍在运行或排队中 */
    public boolean containsPrompt(String promptId) {
        return contains(queueRunning, promptId) || contains(queuePending, promptId);
    }

    private boolean contains(List<List<Object>> queue, String promptId) {
        if (queue == null) return false;
        for (List<Object> entry : queue) {
            // 每项结构: [index, prompt_id, ...]
            if (entry.size() > 1 && promptId.equals(entry.get(1))) {
                return true;
            }
        }
        return false;
    }
}
