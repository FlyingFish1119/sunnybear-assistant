package com.fishsunny.assistant.settings;

/*
 * @Usage CloneModelSettings 合并逻辑与落盘契约测试。
 *        本轮改动把列表语义从「json 全量」变为「内置场景条目实时注入 + json 追加」，
 *        高风险点是三个：
 *        1. getMergedModels 是标准 getter 命名，落盘整对象序列化时极易被 Jackson
 *           当作 mergedModels 属性写进 json（曾真实发生，已用 @JsonIgnore 修掉）——必须回归。
 *        2. 追加条目与内置 key 同名时的覆盖顺序（内置先放、json 后放替换），
 *           错了会把内置条目顶掉或者出现重复 key。
 *        3. 场景 model 为空 / 场景未装配时的跳过行为，注入了空配置等于给执行体埋雷。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/19
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloneModelSettingsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 造一个配置好的场景模型，参数含义同 AISettings 对应字段 */
    private static AISettings scene(String adapter, String model, boolean thinking, String effort) {
        AISettings settings = new AISettings()
                .setAdapterName(adapter)
                .setModel(model)
                .setStream(true)
                .setThinking(thinking);
        return effort == null ? settings : settings.setReasoningEffort(effort);
    }

    /** 模拟 Loader 装配：五个内置场景按固定顺序入表，各场景均配置了模型 */
    private static Map<String, AISettings> fullScenes() {
        LinkedHashMap<String, AISettings> scenes = new LinkedHashMap<>();
        scenes.put(AISettings.CHAT, scene("opencode-go", "glm-5.3", true, null));
        scenes.put(AISettings.CHAT_PRO, scene("opencode-go", "gemini-4-argon", true, null));
        scenes.put(AISettings.OPERATOR, scene("opencode-go-anthropic", "claude-haiku-5-5", false, null));
        scenes.put(AISettings.MISSION, scene("opencode-go", "deepseek-v4.1-flash", false, "high"));
        scenes.put(AISettings.CUB, scene("opencode-go", "mimo-v2.6-flash", false, null));
        return scenes;
    }

    /** 造一条 json 追加条目 */
    private static CloneModelSettings.ModelEntry entry(String key, String model) {
        return new CloneModelSettings.ModelEntry()
                .setKey(key)
                .setDesc("测试追加条目")
                .setAdapterName("opencode-go")
                .setModel(model)
                .setThinking(false);
    }

    @Test
    void builtinEntriesInjectedFromScenesInFixedOrder() {
        CloneModelSettings settings = new CloneModelSettings().setBuiltinScenes(fullScenes());

        List<CloneModelSettings.ModelEntry> merged = settings.getMergedModels();

        assertEquals(5, merged.size(), "五个场景都应注入内置条目，实际: " + merged);
        assertEquals(List.of("chat", "chat_pro", "operator", "mission", "cub"),
                merged.stream().map(CloneModelSettings.ModelEntry::getKey).toList(),
                "内置条目应按 chat→chat_pro→operator→mission→cub 固定顺序排列");

        CloneModelSettings.ModelEntry chat = merged.get(0);
        assertTrue(Boolean.TRUE.equals(chat.getBuiltIn()), "内置条目必须带 builtIn=true 标记");
        assertEquals("opencode-go", chat.getAdapterName());
        assertEquals("glm-5.3", chat.getModel());
        assertTrue(Boolean.TRUE.equals(chat.getThinking()), "场景的 thinking 应原样照搬到内置条目");
        assertNotNull(chat.getDesc(), "内置条目 desc 文案不能为空，主 Agent 靠它选模型");

        CloneModelSettings.ModelEntry mission = merged.get(3);
        assertEquals("high", mission.getReasoningEffort(), "场景的 reasoningEffort 应原样照搬");
    }

    @Test
    void sceneWithoutModelIsSkippedNotInjected() {
        LinkedHashMap<String, AISettings> scenes = new LinkedHashMap<>(fullScenes());
        scenes.put(AISettings.MISSION, scene("opencode-go", "", false, null)); // model 为空的场景
        scenes.remove(AISettings.CUB);                                        // 未装配的场景
        CloneModelSettings settings = new CloneModelSettings().setBuiltinScenes(scenes);

        List<CloneModelSettings.ModelEntry> merged = settings.getMergedModels();

        assertEquals(3, merged.size(), "空配置与未装配的场景都不应注入，实际: "
                + merged.stream().map(CloneModelSettings.ModelEntry::getKey).toList());
        assertEquals(List.of("chat", "chat_pro", "operator"),
                merged.stream().map(CloneModelSettings.ModelEntry::getKey).toList());
    }

    @Test
    void jsonEntriesAppendAfterBuiltin() {
        CloneModelSettings settings = new CloneModelSettings()
                .setBuiltinScenes(fullScenes())
                .setModels(List.of(entry("default", "deepseek-v4.1-flash")));

        List<CloneModelSettings.ModelEntry> merged = settings.getMergedModels();

        assertEquals(6, merged.size(), "内置 5 条 + 追加 1 条");
        assertEquals("default", merged.get(5).getKey(), "追加条目应排在内置条目之后");
        assertNull(merged.get(5).getBuiltIn(), "追加条目不应带 builtIn 标记");
        assertEquals("测试追加条目", merged.get(5).getDesc(), "追加条目字段应原样保留");
    }

    @Test
    void jsonEntryOverridingBuiltinKeepsBuiltinPosition() {
        CloneModelSettings.ModelEntry overriding = entry("mission", "qwen-max");
        overriding.setBuiltIn(true); // json 手写 builtIn，应被归一掉

        CloneModelSettings settings = new CloneModelSettings()
                .setBuiltinScenes(fullScenes())
                .setModels(List.of(entry("default", "deepseek-v4.1-flash"), overriding));

        List<CloneModelSettings.ModelEntry> merged = settings.getMergedModels();

        assertEquals(6, merged.size(), "同名覆盖不应产生重复 key");
        assertEquals("mission", merged.get(3).getKey(),
                "覆盖条目应替换在内置条目的原位置上，而不是排到列表尾部");
        assertEquals("qwen-max", merged.get(3).getModel(), "覆盖后应使用 json 条目的模型");
        assertNull(merged.get(3).getBuiltIn(),
                "覆盖条目不保留内置标记——json 里手写的 builtIn 必须被归一，否则设置页会把它当只读内置条目锁死");
    }

    @Test
    void findEntryResolvesAcrossBuiltinAndExtras() {
        CloneModelSettings settings = new CloneModelSettings()
                .setBuiltinScenes(fullScenes())
                .setModels(List.of(entry("default", "deepseek-v4.1-flash"), entry("mission", "qwen-max")));

        assertEquals("glm-5.3", settings.findEntry("chat").getModel(), "内置 key 应可查");
        assertEquals("deepseek-v4.1-flash", settings.findEntry("default").getModel(), "追加 key 应可查");
        assertEquals("qwen-max", settings.findEntry("mission").getModel(),
                "同名覆盖后 findEntry 必须命中 json 条目（用户覆盖优先）");
        assertNull(settings.findEntry("not-exists"));
        assertNull(settings.findEntry(null), "空 key 不应抛异常");
        assertNull(settings.findEntry(" "));
    }

    @Test
    void describeKeysListsAllMergedEntries() {
        CloneModelSettings settings = new CloneModelSettings()
                .setBuiltinScenes(fullScenes())
                .setModels(List.of(entry("default", "deepseek-v4.1-flash")));

        String keys = settings.describeKeys();

        assertTrue(keys.contains("chat") && keys.contains("chat_pro") && keys.contains("operator")
                        && keys.contains("mission") && keys.contains("cub") && keys.contains("default"),
                "工具参数描述应列出全部可用 key（内置 + 追加）:\n" + keys);
        assertTrue(keys.contains("glm-5.3"), "描述应带模型名，主 Agent 需要看到具体模型:\n" + keys);
        assertFalse(keys.contains("为空"), "非空列表不应出现引导配置的兜底文案");
    }

    @Test
    void describeKeysFallsBackWhenNothingAvailable() {
        CloneModelSettings settings = new CloneModelSettings();

        String keys = settings.describeKeys();

        assertTrue(keys.contains("为空"), "无内置且无追加时应给出引导文案，而不是空串:\n" + keys);
        assertTrue(settings.getMergedModels().isEmpty());
    }

    /**
     * 回归：getMergedModels 是标准 getter 命名，Jackson 整对象序列化时会把它当
     * mergedModels 属性写进 json——曾真实污染过 clone_model_settings.json，
     * @JsonIgnore 修掉后此用例守住该契约：落盘只含追加部分。
     */
    @Test
    void serializationWritesOnlyUserModels() throws Exception {
        CloneModelSettings settings = new CloneModelSettings()
                .setBuiltinScenes(fullScenes())
                .setModels(List.of(entry("default", "deepseek-v4.1-flash")));

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings);

        assertFalse(json.contains("mergedModels"),
                "落盘 json 不应包含 mergedModels 合并视图（只该存追加部分）:\n" + json);
        assertFalse(json.contains("builtinScenes"), "场景 Bean 引用与序列化无关，不应落盘:\n" + json);
        assertTrue(json.contains("\"models\""), "追加列表是落盘主体:\n" + json);
        assertTrue(json.contains("default"), "追加条目应正常写出:\n" + json);
        assertFalse(json.contains("chat_pro"), "内置条目不应出现在落盘内容里:\n" + json);
    }

    /**
     * 反向兼容：线上 json 曾被写进 mergedModels 脏字段，读取必须容忍并忽略它，
     * 不能因为历史脏数据让启动加载 / 后续保存失败。
     */
    @Test
    void deserializationToleratesLegacyMergedModelsField() throws Exception {
        // 上午真实污染文件的形态：models 为空、mergedModels 里躺着一份过期内置快照
        String legacyJson = """
                {
                  "models" : [ ],
                  "mergedModels" : [ {
                    "key" : "chat",
                    "desc" : "用户主对话同款模型",
                    "adapterName" : "opencode-go",
                    "model" : "glm-5.3",
                    "thinking" : true,
                    "reasoningEffort" : null,
                    "builtIn" : true
                  } ]
                }
                """;

        CloneModelSettings settings = objectMapper.readValue(legacyJson, CloneModelSettings.class);

        assertNotNull(settings, "带历史脏字段的 json 必须能正常读入");
        assertTrue(settings.getModels().isEmpty(), "脏的 mergedModels 不应渗进 models 字段");
        assertTrue(settings.getMergedModels().isEmpty(), "未注入场景时脏数据不应参与合并视图");

        // 读入后再落盘一次：脏字段应被清掉，只留干净追加部分
        String resaved = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
        assertFalse(resaved.contains("mergedModels"), "重新保存后脏字段应消失:\n" + resaved);
    }
}
