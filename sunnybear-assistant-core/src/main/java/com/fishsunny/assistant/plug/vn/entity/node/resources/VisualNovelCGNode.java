package com.fishsunny.assistant.plug.vn.entity.node.resources;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:21
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
public class VisualNovelCGNode extends VisualNovelNode {

    public static final String TYPE = "cg";

    public VisualNovelCGNode() {
        setType(TYPE);
    }

    private String img;

    private List<VisualNovelDialogues> dialogues = new ArrayList<>();
}
