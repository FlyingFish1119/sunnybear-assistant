package com.fishsunny.assistant.plug.character.dto;

/*
 * @Usage 词条批量导入结果 DTO，用于前端展示导入统计
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GlossaryImportResult {
    /** 导入总条数 */
    private int total;
    /** 新增条数 */
    private int created;
    /** 覆盖更新条数 */
    private int updated;
    /** 失败条数 */
    private int failed;
}
