package com.fishsunny.assistant.plug.vn.entity.node.dispatch;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:33
 */

import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
public class VisualNovelChoiceNode extends VisualNovelNode {

    public static final String TYPE = "choice";

    public VisualNovelChoiceNode() {
        setType(TYPE);
    }

    private String prompt;

    private List<Options> options = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Options {
        private String text;
        private List<String> requiredEvents = new ArrayList<>();
        private Integer minMatch;
        private List<Remember> remember = new ArrayList<>();
        private String targetNodeId;
    }

    @Data
    @Accessors(chain = true)
    public static class Remember {
        private String key;
        private String desc;
        private String toast;
    }
}
