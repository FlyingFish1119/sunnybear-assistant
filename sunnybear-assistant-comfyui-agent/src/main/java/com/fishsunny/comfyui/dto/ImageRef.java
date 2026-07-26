package com.fishsunny.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageRef {
    private String filename;
    private String subfolder;
    private String type;
}
