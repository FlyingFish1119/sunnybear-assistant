package com.fishsunny.assistant.dto;

/*
 * @Usage 通用工具结构化提问 DTO，用于工具需要向用户提出若干问题（每题可带候选回答、也可自由输入）
 *       时，把提问内容下发给前端展示弹窗。用户答完或取消后，结果通过 ToolQuestionAnswer 回传。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/8
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Accessors(chain = true)
public class ToolQuestion {

    /** 提问请求的唯一标识，与回传的 ToolQuestionAnswer.id 对应 */
    private String id;
    /** 工具名称 */
    private String toolName;
    /** 全局引导语（markdown，可为空），展示在题目列表上方 */
    private String message;
    /** 超时时间（秒）；为空或 <=0 表示不超时，一直等待用户作答 */
    private Integer timeout;
    /** 问题列表 */
    private List<Question> questions = new ArrayList<>();

    public ToolQuestion loadInfo(String toolName, String message, Integer timeout) {
        this.id = UUID.randomUUID().toString();
        this.toolName = toolName;
        this.message = message;
        this.timeout = timeout;
        return this;
    }

    @Data
    @Accessors(chain = true)
    public static class Question {
        /** 问题的稳定标识（列表顺序自增），前端回传 answers 时按此 key 对齐 */
        private String key;
        /** 问题内容 */
        private String q;
        /** 候选回答（用户可直接点选，也可自由输入）；可为空 = 只能自由输入 */
        private List<String> options = new ArrayList<>();
    }
}
