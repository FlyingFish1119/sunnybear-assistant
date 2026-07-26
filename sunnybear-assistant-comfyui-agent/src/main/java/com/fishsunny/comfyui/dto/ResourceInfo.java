package com.fishsunny.comfyui.dto;

import lombok.Data;

import java.util.*;

/**
 * ComfyUI 可用资源摘要。
 * <p>
 * 从 /object_info 全面提取所有节点的模型文件选项，按固定映射表归类。
 */
@Data
public class ResourceInfo {

    /** 从 /object_info 特定节点提取的经典资源（向后兼容） */
    private List<String> checkpoints;
    private List<String> loras;
    private List<String> vaes;
    private List<String> samplers;
    private List<String> schedulers;

    /** 从 /object_info 所有节点全面提取的模型文件，按类别去重分组 */
    private Map<String, List<String>> models;

    // ==================== 经典节点类型 ====================

    private static final String NODE_CHECKPOINT = "CheckpointLoaderSimple";
    private static final String NODE_LORA       = "LoraLoader";
    private static final String NODE_VAE        = "VAELoader";
    private static final String NODE_KSAMPLER   = "KSampler";

    // ==================== 字段名 → 类别名 映射表 ====================

    /** /object_info 中的字段名到友好类别名的映射。未在此表中的字段名直接作为类别名。 */
    private static final Map<String, String> FIELD_TO_CATEGORY = new LinkedHashMap<>();
    static {
        // 带 s 结尾的
        FIELD_TO_CATEGORY.put("ckpt_name",          "checkpoints");
        FIELD_TO_CATEGORY.put("lora_name",           "loras");
        FIELD_TO_CATEGORY.put("lora",                "loras");
        FIELD_TO_CATEGORY.put("vae_name",            "vaes");
        FIELD_TO_CATEGORY.put("vae",                 "vaes");
        FIELD_TO_CATEGORY.put("sampler_name",        "samplers");
        FIELD_TO_CATEGORY.put("scheduler",           "schedulers");
        // 不带 s 结尾的（保持和 ComfyUI models 目录一致）
        FIELD_TO_CATEGORY.put("model_name",          null); // 保留原字段名
        FIELD_TO_CATEGORY.put("model",               null); // 保留原字段名
        FIELD_TO_CATEGORY.put("control_net_name",    "controlnet");
        FIELD_TO_CATEGORY.put("embedding_name",      "embeddings");
        FIELD_TO_CATEGORY.put("hypernetwork_name",   "hypernetworks");
        FIELD_TO_CATEGORY.put("upscale_model_name",  "upscale_models");
        FIELD_TO_CATEGORY.put("style_model_name",    "style_models");
        FIELD_TO_CATEGORY.put("gligen_name",         "gligen");
        FIELD_TO_CATEGORY.put("photomaker_name",     "photomaker");
        FIELD_TO_CATEGORY.put("diffusion_model_name","diffusion_models");
        FIELD_TO_CATEGORY.put("text_encoder_name",   "text_encoders");
        FIELD_TO_CATEGORY.put("clip_name",           "text_encoders");
        FIELD_TO_CATEGORY.put("t5_name",             "text_encoders");
        FIELD_TO_CATEGORY.put("gemma_path",          "text_encoders");
        FIELD_TO_CATEGORY.put("clip_vision_name",    "clip_vision");
        FIELD_TO_CATEGORY.put("bbox_detector",       "detectors");
    }

    /** 模型文件常见扩展名 */
    private static final Set<String> MODEL_EXTENSIONS = Set.of(
            ".safetensors", ".ckpt", ".pt", ".pth", ".bin", ".yaml", ".sft", ".onnx"
    );

    /** 占位文件前缀 */
    private static final String PLACEHOLDER_PREFIX = "put_";

    // ==================== 构建方法 ====================

    /**
     * 从 /object_info 的节点 Map 中提取全部资源信息。
     */
    public static ResourceInfo from(Map<String, NodeInfo> nodes) {
        ResourceInfo r = new ResourceInfo();

        // 1. 经典资源
        r.checkpoints = getOptions(nodes, NODE_CHECKPOINT, "ckpt_name");
        r.loras       = getOptions(nodes, NODE_LORA, "lora_name");
        r.vaes        = getOptions(nodes, NODE_VAE, "vae_name");

        NodeInfo ksampler = nodes.get(NODE_KSAMPLER);
        if (ksampler != null) {
            r.samplers   = ksampler.getOptions("sampler_name");
            r.schedulers = ksampler.getOptions("scheduler");
        }

        // 2. 全面扫描所有节点，提取所有模型文件
        Map<String, Set<String>> categoryMap = new LinkedHashMap<>();

        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            String nodeName = entry.getKey();
            NodeInfo nodeInfo = entry.getValue();
            if (nodeInfo.getInput() == null || nodeInfo.getInput().getRequired() == null) continue;

            for (Map.Entry<String, List<Object>> fieldEntry : nodeInfo.getInput().getRequired().entrySet()) {
                String fieldName = fieldEntry.getKey();
                List<String> options = extractModelOptions(fieldEntry.getValue());
                if (options.isEmpty()) continue;

                // 用字段名查映射表确定类别
                String category = resolveCategory(fieldName, nodeName);

                categoryMap.computeIfAbsent(category, k -> new LinkedHashSet<>())
                        .addAll(options);
            }
        }

        // 转为有序 List
        Map<String, List<String>> models = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : categoryMap.entrySet()) {
            List<String> list = new ArrayList<>(entry.getValue());
            list.sort(String::compareToIgnoreCase);
            models.put(entry.getKey(), list);
        }
        r.models = models;

        // 3. 经典字段为空的，从 models 补充
        fillFromModels(r, models);

        return r;
    }

    /** 如果经典字段为空，从 models 中补充 */
    private static void fillFromModels(ResourceInfo r, Map<String, List<String>> models) {
        if ((r.checkpoints == null || r.checkpoints.isEmpty()) && models.containsKey("checkpoints")) {
            r.checkpoints = models.get("checkpoints");
        }
        if ((r.loras == null || r.loras.isEmpty()) && models.containsKey("loras")) {
            r.loras = models.get("loras");
        }
        if ((r.vaes == null || r.vaes.isEmpty()) && models.containsKey("vaes")) {
            r.vaes = models.get("vaes");
        }
    }

    /** 根据字段名（和节点名）解析类别名 */
    private static String resolveCategory(String fieldName, String nodeName) {
        // 1. 查映射表
        String mapped = FIELD_TO_CATEGORY.get(fieldName);
        if (mapped != null) return mapped;

        // 2. 映射表里没有但值为 null 表示"保留原字段名"
        if (FIELD_TO_CATEGORY.containsKey(fieldName)) return fieldName;

        // 3. 按节点名兜底
        String nl = nodeName.toLowerCase();
        if (nl.contains("vae"))                           return "vaes";
        if (nl.contains("lora"))                          return "loras";
        if (nl.contains("checkpoint"))                    return "checkpoints";
        if (nl.contains("control"))                       return "controlnet";
        if (nl.contains("upscale"))                       return "upscale_models";
        if (nl.contains("style"))                         return "style_models";
        if (nl.contains("sam") && nl.contains("loader"))  return "sams";
        if (nl.contains("embed"))                         return "embeddings";
        if (nl.contains("hypernet"))                      return "hypernetworks";
        if (nl.contains("gligen"))                        return "gligen";
        if (nl.contains("photo"))                         return "photomaker";
        if (nl.contains("diffusion") && nl.contains("model")) return "diffusion_models";
        if (nl.contains("t5") || nl.contains("text_enc") || nl.contains("clip") || nl.contains("gemma"))
                                                          return "text_encoders";

        // 4. 最终兜底：用字段名本身
        return fieldName;
    }

    /**
     * 从字段定义中提取模型文件选项列表。
     * ComfyUI 字段格式: [["option1", "option2", ...], {metadata}]
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractModelOptions(Object fieldDef) {
        if (!(fieldDef instanceof List<?> list) || list.size() < 2) {
            return List.of();
        }
        Object first = list.get(0);
        if (!(first instanceof List<?> options)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (Object opt : options) {
            if (!(opt instanceof String s)) continue;
            if (s.startsWith(PLACEHOLDER_PREFIX)) continue;
            String lower = s.toLowerCase();
            for (String ext : MODEL_EXTENSIONS) {
                if (lower.endsWith(ext)) { result.add(s); break; }
            }
        }
        return result;
    }

    private static List<String> getOptions(Map<String, NodeInfo> nodes, String node, String field) {
        NodeInfo info = nodes.get(node);
        return info != null ? info.getOptions(field) : List.of();
    }
}
