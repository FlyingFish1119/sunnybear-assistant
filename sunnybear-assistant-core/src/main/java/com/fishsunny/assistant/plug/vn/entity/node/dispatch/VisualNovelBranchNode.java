package com.fishsunny.assistant.plug.vn.entity.node.dispatch;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:38
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
public class VisualNovelBranchNode extends VisualNovelNode {

    public static final String TYPE = "branch";

    private String defaultTargetNodeId;

    public VisualNovelBranchNode() {
        setType(TYPE);
    }

    private List<Branch> branches = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Branch {
        private Integer priority;
        private List<String> requiredEvents = new ArrayList<>();
        private Integer minMatch;
        private String toast;
        private String targetNodeId;
    }
}
