package com.fishsunny.assistant.settings;

/*
 * @Usage 记忆设置 —— 记忆注入开关
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/7
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MemorySettings {

    /** 是否在对话中自动注入核心记忆；关闭后不再注入（不影响记忆 CRUD 与问候语个性化） */
    private Boolean enable;
}
