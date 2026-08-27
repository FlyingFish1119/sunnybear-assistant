package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观导入/导出服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.dto.WorldFileData;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;

public interface WorldImportExportService {

    /**
     * 导出世界观为文件结构（不含头像/背景图/ID，知识知晓角色按名称引用）。
     *
     * @param worldId 世界观 ID
     */
    WorldFileData exportWorld(String worldId);

    /**
     * 导入世界观：targetWorldId 为空 = 新建世界观；否则覆盖该世界观
     * （更新本体、替换其全部角色与知识，保留背景图和会话绑定）。
     *
     * @param data          文件数据（AI 可编辑，宽容解析）
     * @param targetWorldId 覆盖目标世界观 ID（可为空）
     * @return 导入完成后的世界观
     */
    WorldInfo importWorld(WorldFileData data, String targetWorldId);
}
