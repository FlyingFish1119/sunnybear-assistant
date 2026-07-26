package com.fishsunny.comfyui.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** GET /view 获取图片的返回 */
@Data
@Accessors(chain = true)
public class ViewImageResult {
    private String filename;
    private String type;
    private String contentType;
    private int size;
    private String base64;
}
