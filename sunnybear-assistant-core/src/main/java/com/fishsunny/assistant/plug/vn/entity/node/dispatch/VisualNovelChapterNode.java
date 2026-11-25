package com.fishsunny.assistant.plug.vn.entity.node.dispatch;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 11:20
 */

import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
public class VisualNovelChapterNode extends VisualNovelNode {

    public static final String TYPE = "chapter";

    public VisualNovelChapterNode() {
        setType(TYPE);
    }

    // 目标章节ID，例如 "2-章节目录结构"
    private String targetChapter;

    // 目标章节内的起始节点ID，例如 "node_1"
    private String targetNodeId;

    // 可选提示文本
    private String toast;
}
