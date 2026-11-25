package com.fishsunny.assistant.plug.vn.entity.node;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:21
 */

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelBranchNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChapterNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChoiceNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelNormalNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelCGNode;
import lombok.Data;
import lombok.experimental.Accessors;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = VisualNovelNormalNode.class, name = VisualNovelNormalNode.TYPE),
        @JsonSubTypes.Type(value = VisualNovelCGNode.class, name = VisualNovelCGNode.TYPE),
        @JsonSubTypes.Type(value = VisualNovelChoiceNode.class, name = VisualNovelChoiceNode.TYPE),
        @JsonSubTypes.Type(value = VisualNovelBranchNode.class, name = VisualNovelBranchNode.TYPE),
        @JsonSubTypes.Type(value = VisualNovelChapterNode.class, name = VisualNovelChapterNode.TYPE)
})
@Data
@Accessors(chain = true)
public abstract class VisualNovelNode {

    private String type;

    private String nextNodeId;
}
