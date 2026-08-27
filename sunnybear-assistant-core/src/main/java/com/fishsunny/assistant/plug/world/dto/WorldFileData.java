package com.fishsunny.assistant.plug.world.dto;

/*
 * @Usage 世界观导入/导出文件结构（顶层容器）
 *        导出：不含头像、背景图、ID、时间戳，aiSettings 使用对象形式，知识按角色名关联
 *        导入：宽容解析 AI 编辑后的 JSON，所有 ID 重新生成
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorldFileData {

    /** 文件格式版本 */
    private Integer version = 1;

    /** 文件类型标识（识别文件用，导入时缺失不阻塞） */
    private String type = "sunnybear-world";

    /** 世界观本体 */
    private WorldFileWorld world;

    /** 角色列表（不含头像） */
    private List<WorldFileCharacter> characters = new ArrayList<>();

    /** 知识列表（知晓角色按名称引用） */
    private List<WorldFileKnowledge> knowledge = new ArrayList<>();
}
