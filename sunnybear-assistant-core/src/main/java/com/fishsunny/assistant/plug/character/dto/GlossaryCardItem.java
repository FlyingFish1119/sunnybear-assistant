package com.fishsunny.assistant.plug.character.dto;

/*
 * @Usage 角色卡词条项 —— character_authoring 工具导入导出词条时的载体。
 *        只承载业务三字段（关键词 / 描述 / 内容）；id、characterId、创建与更新时间
 *        由服务端写入时自行生成，不进文件——避免导出的 id 被误当成可往返的标识。
 *        解析时忽略未知字段，因此带 id / 时间戳的旧文件也能照样导入。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/29
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlossaryCardItem {

    /** 关键词 */
    private String keyword;

    /** 词条描述（简短说明，注入系统提示词） */
    private String desc;

    /** 词条内容（完整内容，AI 工具查询返回） */
    private String content;
}
