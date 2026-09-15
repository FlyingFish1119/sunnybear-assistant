package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 角色编写工具包 —— 让主助手直接读写角色设定（新建 / 修改 / 查看）。
 *        与角色对话工具（骰子/词条/战斗）相反：本工具集默认对主对话开放，
 *        且不要求上下文中的 character key（主对话没有绑定角色）。
 *        可用 plug.character.tool.authoring.enable 关闭。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.settings.AISettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "plug.character.tool.authoring.enable", havingValue = "true", matchIfMissing = true)
public class CharacterAuthoringToolKit extends ToolKit {

    public CharacterAuthoringToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "角色编写";
    }

    @Override
    public String description() {
        return "让主助手新建、修改、查看角色设定（名称、人设提示词、预设、主题色、工具开关）及其词条";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }

    // ==================== 工具集定义 ====================
    // 与前端 character_settings.html 的 toolCategories 保持一致：前端只给这两组开关，
    // 这里也按同样的名单精确组装 character_info.tools，不接收任意工具名。

    /** 角色词条（前端 key=glossary） */
    static final List<String> GLOSSARY_TOOLS = List.of(
            "character_glossary_create_tool",
            "character_glossary_update_tool",
            "character_glossary_delete_tool",
            "character_glossary_query_tool");

    /** GM 组件（前端 key=gm） */
    static final List<String> GM_TOOLS = List.of(
            "calculate_tool",
            "character_db_query",
            "character_db_execute",
            "d20_check",
            "ndm_roll",
            "battle_engine_tool");

    // ==================== 共用辅助 ====================

    /** 解析角色 aiSettings JSON；为空或解析失败返回一个空 AISettings（便于保留其它字段后写回） */
    static AISettings parseAiSettings(ObjectMapper objectMapper, CharacterInfo character) {
        if (character == null || !StringUtils.hasText(character.getAiSettings())) {
            return new AISettings();
        }
        try {
            AISettings settings = objectMapper.readValue(character.getAiSettings(), AISettings.class);
            return settings == null ? new AISettings() : settings;
        } catch (Exception e) {
            return new AISettings();
        }
    }

    /** 序列化对象为 JSON 字符串，失败返回 fallback */
    static String writeJson(ObjectMapper objectMapper, Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 把角色渲染成便于主助手阅读的 Markdown */
    static String describe(ObjectMapper objectMapper, CharacterInfo character) {
        if (character == null) {
            return "角色不存在。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 角色：").append(StringUtils.hasText(character.getName()) ? character.getName() : "(未命名)").append("\n\n");
        sb.append("- id: `").append(character.getId()).append("`\n");
        if (StringUtils.hasText(character.getMainColor())) {
            sb.append("- 主题色: ").append(character.getMainColor()).append("\n");
        }
        if (character.getOpacity() != null) {
            sb.append("- 透明度: ").append(character.getOpacity()).append("\n");
        }
        AISettings ai = parseAiSettings(objectMapper, character);
        sb.append("\n### 人设提示词（aiSettings.prompt）\n\n");
        sb.append(StringUtils.hasText(ai.getPrompt()) ? ai.getPrompt() : "(空)").append("\n");
        sb.append("\n### 预设（preset，每轮拼在人设前）\n\n");
        sb.append(StringUtils.hasText(character.getPreset()) ? character.getPreset() : "(空)").append("\n");
        sb.append("\n### 工具开关\n\n");
        sb.append(describeTools(objectMapper, character.getTools())).append("\n");
        if (StringUtils.hasText(ai.getAdapterName()) || StringUtils.hasText(ai.getModel())) {
            sb.append("\n### 模型\n\n")
              .append("- adapter: ").append(ai.getAdapterName()).append("\n")
              .append("- model: ").append(ai.getModel()).append("\n");
        }
        return sb.toString();
    }

    /** 按前端的两组开关精确组装 tools JSON：开启的组，其下所有工具置 true，其余不写入 */
    static String buildToolsJson(ObjectMapper objectMapper, boolean glossaryEnabled, boolean gmEnabled) {
        Map<String, Boolean> tools = new LinkedHashMap<>();
        if (glossaryEnabled) {
            GLOSSARY_TOOLS.forEach(name -> tools.put(name, true));
        }
        if (gmEnabled) {
            GM_TOOLS.forEach(name -> tools.put(name, true));
        }
        return writeJson(objectMapper, tools, "{}");
    }

    /** 解析 tools JSON 为 Map；空/失败返回空 Map */
    static Map<String, Boolean> parseToolsMap(ObjectMapper objectMapper, String toolsJson) {
        if (!StringUtils.hasText(toolsJson)) {
            return Map.of();
        }
        try {
            Map<String, Boolean> map = objectMapper.readValue(toolsJson, new TypeReference<Map<String, Boolean>>() {});
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 某组工具是否全部开启 */
    static boolean groupEnabled(Map<String, Boolean> tools, List<String> group) {
        for (String name : group) {
            if (!Boolean.TRUE.equals(tools.get(name))) {
                return false;
            }
        }
        return true;
    }

    /** 把 tools JSON 渲染成两组开关的开关状态 */
    static String describeTools(ObjectMapper objectMapper, String toolsJson) {
        Map<String, Boolean> tools = parseToolsMap(objectMapper, toolsJson);
        return "- 角色词条：" + (groupEnabled(tools, GLOSSARY_TOOLS) ? "开启" : "关闭") + "\n"
                + "- GM 组件：" + (groupEnabled(tools, GM_TOOLS) ? "开启" : "关闭");
    }

    /** 把角色的词条列表渲染成 Markdown（keyword + desc），无词条时返回提示 */
    static String describeGlossary(CharacterGlossaryService glossaryService, CharacterInfo character) {
        List<CharacterGlossary> glossaries = glossaryService.listByCharacterId(character.getId());
        if (glossaries == null || glossaries.isEmpty()) {
            return "（暂无词条）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(glossaries.size()).append(" 条：\n");
        for (CharacterGlossary g : glossaries) {
            sb.append("- ").append(StringUtils.hasText(g.getKeyword()) ? g.getKeyword() : "(无关键词)");
            if (StringUtils.hasText(g.getDesc())) {
                sb.append("：").append(g.getDesc().replaceAll("\\s+", " "));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 把单条词条渲染成 Markdown（含完整内容） */
    static String describeGlossaryDetail(CharacterGlossary g) {
        if (g == null) {
            return "词条不存在。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 词条：").append(StringUtils.hasText(g.getKeyword()) ? g.getKeyword() : "(无关键词)").append("\n\n");
        if (StringUtils.hasText(g.getDesc())) {
            sb.append("- 描述: ").append(g.getDesc()).append("\n");
        }
        sb.append("\n").append(StringUtils.hasText(g.getContent()) ? g.getContent() : "(无内容)");
        return sb.toString();
    }
}
