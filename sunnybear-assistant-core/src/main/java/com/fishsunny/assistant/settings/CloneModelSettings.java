package com.fishsunny.assistant.settings;

/*
 * @Usage 执行体（clone_assistant_tool）可选模型列表
 *
 *        每条记录以 key 对外：主 Agent 调用工具时传 key，工具按 key 查表得到
 *        「适配器 + 模型 + thinking / reasoningEffort」组装执行设置。
 *
 *        列表 = 内置场景模型 + json 追加：
 *        - 内置条目：chat / chat_pro / operator / mission / cub 五个场景模型（排除 task）实时注入，
 *          现调现生成——场景设置在设置页热改后，本列表下一轮对话即自动跟随，无需重启；
 *          场景未装配或 model 为空时跳过注入（空配置拿去执行必失败，不注入比报错好）。
 *        - 追加条目：clone_model_settings.json 的 models 数组，由设置页（AI 设置 → 执行体模型列表）
 *          CRUD 管理，整表写回，文件只存追加部分；key 撞场景名时覆盖内置条目上的那条
 *          （用户显式配置优先，覆盖条目可编辑删除，删除后内置条目自动回来）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/19
 */

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloneModelSettings {

    /**
     * 内置注入的场景 key，按展示顺序排列；task 排除——编排任务模型不参与执行体选型。
     */
    private static final List<String> BUILTIN_SCENES = List.of(
            AISettings.CHAT, AISettings.CHAT_PRO, AISettings.OPERATOR, AISettings.MISSION, AISettings.CUB);

    /**
     * 内置条目的 desc 文案：描述场景角色定位而非具体模型名——模型名由场景 Bean 实时决定，
     * desc 只负责告诉主 Agent「这个位置该选什么档位的活」，场景换模型文案不跟着变。
     */
    private static final Map<String, String> BUILTIN_DESCS = Map.of(
            AISettings.CHAT, "用户主对话同款模型，通用能力强，适合大多数日常执行任务",
            AISettings.CHAT_PRO, "旗舰深度思考模型，复杂任务（架构设计、疑难分析）首选",
            AISettings.OPERATOR, "为屏幕视觉交互（Computer Use / Browser Use）调优的模型，涉及看屏幕、点界面元素的任务用它",
            AISettings.MISSION, "常规执行型任务的均衡选择，代码与文件类工作默认拿它",
            AISettings.CUB, "最轻量的快速模型，简单查询、格式转换等小任务用它");

    /** json 里的用户追加条目（原样字段，落盘只落这部分） */
    private List<ModelEntry> models = new ArrayList<>();

    /**
     * 内置场景模型引用，由 Loader 装配时注入，key 与 BUILTIN_SCENES 一致。
     * 持有的是运行时热改的 Bean 本体，故内置条目能实时跟随场景设置。
     */
    @JsonIgnore
    private Map<String, AISettings> builtinScenes = new LinkedHashMap<>();

    public CloneModelSettings setModels(List<ModelEntry> models) {
        this.models = models == null ? new ArrayList<>() : models;
        return this;
    }

    public CloneModelSettings setBuiltinScenes(Map<String, AISettings> builtinScenes) {
        this.builtinScenes = builtinScenes == null ? new LinkedHashMap<>() : builtinScenes;
        return this;
    }

    /**
     * 合并视图：内置条目在前（场景 Bean 现场生成）、json 追加条目在后。
     * key 撞场景名时 json 条目替换内置位置上的条目（用户覆盖）；
     * 用户条目不携带 builtIn 标记——内置与否由运行时注入决定，json 里手写的 builtIn 会被归一掉。
     * <p>
     * 注意 @JsonIgnore：方法名是标准 getter 形态，不加的话落盘整对象序列化时
     * 会把本方法返回值当作 mergedModels 属性写进 json——合并视图只该内存现拼，不该落盘。
     */
    @JsonIgnore
    public List<ModelEntry> getMergedModels() {
        LinkedHashMap<String, ModelEntry> merged = new LinkedHashMap<>();
        for (String scene : BUILTIN_SCENES) {
            AISettings settings = builtinScenes.get(scene);
            if (settings == null || !StringUtils.hasText(settings.getModel())) {
                continue;
            }
            merged.put(scene, new ModelEntry()
                    .setKey(scene)
                    .setDesc(BUILTIN_DESCS.get(scene))
                    .setAdapterName(settings.getAdapterName())
                    .setModel(settings.getModel())
                    .setThinking(Boolean.TRUE.equals(settings.getThinking()))
                    .setReasoningEffort(settings.getReasoningEffort())
                    .setBuiltIn(true));
        }
        if (models != null) {
            for (ModelEntry entry : models) {
                if (entry == null || !StringUtils.hasText(entry.getKey())) {
                    continue;
                }
                merged.put(entry.getKey(), entry.setBuiltIn(null));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 按 key 在合并视图中查条目；key 无效或找不到时返回 null，由调用方决定报错文案 */
    public ModelEntry findEntry(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return getMergedModels().stream().filter(m -> key.equals(m.getKey())).findFirst().orElse(null);
    }

    /**
     * 拼给工具参数描述看的 key 清单文案，每行一条：key（desc —— model @ adapter）。
     * 合并后内置条目常驻，列表为空只会在场景全未配置且无追加时出现，文案相应说明原因。
     */
    public String describeKeys() {
        List<ModelEntry> merged = getMergedModels();
        if (merged.isEmpty()) {
            return "当前执行体模型列表为空（场景模型均未配置且无追加模型），传入任何 model 都会报错；"
                    + "请让用户到设置页「AI 设置」先为各场景配置模型，或到「执行体模型列表」追加条目后再使用本工具";
        }
        StringBuilder sb = new StringBuilder();
        for (ModelEntry m : merged) {
            sb.append("- ").append(m.getKey());
            if (StringUtils.hasText(m.getDesc())) {
                sb.append("：").append(m.getDesc().trim());
            }
            sb.append("（").append(m.getModel()).append(" @ ").append(m.getAdapterName()).append("）\n");
        }
        return sb.toString().trim();
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {

        /** 工具入参用的模型标识，列表内唯一 */
        private String key;

        /** 用途描述：拼进工具参数说明，主 Agent 按它为任务选模型 */
        private String desc;

        private String adapterName;

        private String model;

        /** 执行时是否开启思考 */
        private Boolean thinking;

        /** low / high / max；null 或空 = 不写入请求体（由 API 使用默认值） */
        private String reasoningEffort;

        /**
         * 内置场景条目标记：由场景模型实时注入生成，设置页只读展示、不参与落盘。
         * 用户追加条目保持 null（getMergedModels 会归一掉 json 里手写的值）。
         */
        private Boolean builtIn;
    }
}
