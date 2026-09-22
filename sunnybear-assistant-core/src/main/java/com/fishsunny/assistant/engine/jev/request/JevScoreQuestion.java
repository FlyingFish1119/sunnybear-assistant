package com.fishsunny.assistant.engine.jev.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Score 问题：在有序、可描述的分级上给内容定一个位置。
 * <p>
 * criteria 为有序的层级描述数组，从低到高，至少 2 层、最多 10 层。
 * 数组中每个元素的下标即该层级的编号（从 0 开始），模型看不到编号，只看到描述。
 * <p>
 * 每个元素可以是字符串、对象或数组；需要「覆盖什么 + 示例」时用对象。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevScoreQuestion extends JevQuestion {

    /**
     * 层级描述，有序数组，下标即层级编号。
     */
    private List<Object> criteria = new ArrayList<>();

    public JevScoreQuestion() {
        super.type = "score";
    }

    public JevScoreQuestion setCriteria(List<Object> criteria) {
        this.criteria = criteria == null ? new ArrayList<>() : criteria;
        return this;
    }
}
