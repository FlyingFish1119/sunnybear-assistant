package com.fishsunny.assistant.dto;

/*
 * @Usage 通用工具确认结果 DTO，用于前端回传用户的确认结果
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 06:30
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ToolConfirm {
    /** 确认请求的唯一标识 */
    private String id;
    /** 用户是否确认 */
    private boolean confirm;
}
