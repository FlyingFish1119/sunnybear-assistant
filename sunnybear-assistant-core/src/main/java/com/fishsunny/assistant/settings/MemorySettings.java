package com.fishsunny.assistant.settings;

/*
 * @Usage 记忆设置
 *
 *        只有三个开关：注入、自动整理、空闲小时数。
 *        其余（单次条数上限、删除上限这类内部护栏）留在代码里当常量，不对外暴露配置。
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

    public MemorySettings setEnable(Boolean enable) {
        this.enable = Boolean.TRUE.equals(enable);
        return this;
    }

    public Boolean getEnable() {
        return Boolean.TRUE.equals(this.enable);
    }

    /**
     * 自动记忆整理开关：chat 会话静默满 {@link #getAutoRecordIdleHours()} 小时后，
     * 后台用完整助手提示词 + 整段历史回顾一次，把值得长期记住的内容写进核心记忆。
     * <p>默认关闭：记忆会长期注入上下文，先干跑几轮、确认记出来的东西靠谱再开。
     */
    private Boolean enableAutoRecord = false;

    public MemorySettings setEnableAutoRecord(Boolean enableAutoRecord) {
        this.enableAutoRecord = Boolean.TRUE.equals(enableAutoRecord);
        return this;
    }

    public Boolean getEnableAutoRecord() {
        return Boolean.TRUE.equals(this.enableAutoRecord);
    }

    /**
     * 空闲小时数：最后一轮对话之后连续静默这么久，才认为这段会话结束并触发整理。
     * 每轮对话都会把到期时间往后推，聊着的时候永远不会触发。默认 24，&lt;= 0 取默认值。
     */
    private Integer autoRecordIdleHours;

    public MemorySettings setAutoRecordIdleHours(Integer autoRecordIdleHours) {
        this.autoRecordIdleHours = autoRecordIdleHours;
        return this;
    }

    public Integer getAutoRecordIdleHours() {
        return autoRecordIdleHours == null || autoRecordIdleHours <= 0 ? 24 : autoRecordIdleHours;
    }
}
