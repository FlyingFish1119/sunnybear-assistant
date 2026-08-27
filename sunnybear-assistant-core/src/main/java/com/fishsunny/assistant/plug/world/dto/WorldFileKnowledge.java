package com.fishsunny.assistant.plug.world.dto;

/*
 * @Usage 世界观导入/导出文件中的知识条目（知晓角色按「角色名」引用，导入时映射回新生成的 ID）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorldFileKnowledge {

    /** 知识标题 */
    private String title;

    /** 知识内容 */
    private String content;

    /** 知晓该知识的角色名列表（空 = 无角色知晓） */
    private List<String> characters = new ArrayList<>();
}
