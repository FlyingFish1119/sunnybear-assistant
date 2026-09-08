package com.fishsunny.assistant.dto;

/*
 * @Usage 通用工具结构化提问结果 DTO，用于前端回传用户的作答结果。
 *        要么逐题给出 answers，要么 cancelled=true（用户关闭/退出弹窗，视为想自由交谈）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/8
 */

import com.fishsunny.assistant.exception.UserException;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ToolQuestionAnswer {

    /** 与 ToolQuestion.id 对应的唯一标识 */
    private String id;

    /** 用户是否取消作答（关闭/退出弹窗） */
    private Boolean cancelled = false;

    /** 各题的作答，按 key 对齐；cancel 时可空 */
    private List<Item> answers = new ArrayList<>();

    public ToolQuestionAnswer setId(String id) {
        if (!StringUtils.hasText(id)) {
            throw new UserException("提问请求的唯一标识不能为空");
        }
        this.id = id;
        return this;
    }

    public ToolQuestionAnswer setCancelled(Boolean cancelled) {
        this.cancelled = Boolean.TRUE.equals(cancelled);
        return this;
    }

    @Data
    @Accessors(chain = true)
    public static class Item {
        /** 问题 key，对应 ToolQuestion.questions[].key */
        private String key;
        /** 用户的选择 / 输入内容 */
        private String answer;
    }
}
