package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 核心记忆服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;

import java.util.List;

public interface MemoryService {

    /** 默认分组名：不带分组的新增都落这里（与 MemoryRecord 常量同源） */
    String GROUP_UNCLASSIFIED = MemoryRecord.GROUP_UNCLASSIFIED;

    /**
     * 添加或更新记忆（通过 mode 参数切换）
     *
     * @param id        记忆 ID（add 模式可为 null，系统自动生成；update 模式必填）
     * @param content   记忆内容
     * @param groupName 分组名（可空：add 落「未分类」，update 表示不动原分组）
     * @param mode      操作模式：add（新增）或 update（修改）
     * @return 保存后的记忆记录
     */
    MemoryRecord addOrUpdateMemory(Integer id, String content, String groupName, String mode);

    /**
     * 根据 ID 查询记忆（不修改）
     *
     * @param id 记忆 ID
     * @return 记忆记录，不存在则返回 null
     */
    MemoryRecord getMemoryById(Integer id);

    /**
     * 根据 ID 删除记忆
     *
     * @param id 记忆 ID
     * @return 被删除的记忆记录，不存在则返回 null
     */
    MemoryRecord deleteMemory(Integer id);

    /**
     * 获取全部记忆，按创建时间降序
     *
     * @return 记忆列表
     */
    List<MemoryRecord> getAllMemories();

    /**
     * 获取全部记忆内容，按组分节拼接为字符串，用于注入系统提示词
     *
     * @return 格式化的记忆字符串
     */
    String buildMemorySection();

    /**
     * 分组重命名：该组下所有记忆一起改名
     *
     * @param oldName 原组名
     * @param newName 新组名
     * @return 受影响条数（原组名不存在返回 0）
     */
    int renameGroup(String oldName, String newName);

    List<MemoryRecord> autoGroup();
}
