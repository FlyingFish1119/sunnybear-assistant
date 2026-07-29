package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 任务提示词实体 — 预定义的 step 系统提示词模板
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class TaskPrompt {

    public static final TaskPrompt DEFAULT_PROMPT = new TaskPrompt()
            .setPrompt("""
                    你是一个多步骤自动化流水线中的单一步骤执行 AI。

                        ## 职责边界
                        你的唯一职责是完成当前分配给你的步骤。具体步骤内容见用户消息。
                        不得尝试完成整体目标、提前执行后续步骤，或给出整体解决方案。
                        只输出与本步骤直接相关的内容，不扩展、不越界。

                        ## 完成要求
                        完成本步骤后，必须输出一段「步骤总结」：
                        1. 本步骤做了什么
                        2. 产出了什么关键结果
                        3. 下一步需要什么信息或条件\
                    """
            ).setType("default")
            .setDescription("默认提示词");

    /** 提示词类型，作为主键 — 任意字符串，由用户自由定义 */
    private String type;

    /** 系统提示词 — 纯角色/行为指令，不含步骤占位符 */
    private String prompt;

    /** 该类型提示词的用途说明 */
    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public TaskPrompt() {
    }

    public TaskPrompt(String type, String prompt, String description) {
        this.type = type;
        this.prompt = prompt;
        this.description = description;
    }
}
