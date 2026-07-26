package com.fishsunny.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptResponse {
    @JsonProperty("prompt_id")
    private String promptId;

    private Integer number;

    @JsonProperty("node_errors")
    private Map<String, Object> nodeErrors;
}
