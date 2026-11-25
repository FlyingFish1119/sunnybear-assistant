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
public class VisualNovelNormalNode extends VisualNovelNode {

    public static final String TYPE = "normal";

    public VisualNovelNormalNode() {
        setType(TYPE);
    }

    private String background;

    private List<VisualNovelDialogues> dialogues = new ArrayList<>();

    private List<Character> characters = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Character {
        private String id;
        private String sprite;
        private Integer position;
        private Integer z;
        private Boolean flipX;
        private Double scale;
    }
}
