package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 保存视觉小说本体的大纲与元数据（data.json） —— title 命中已有小说则更新，未命中则新建。
 *        字段全部可选：传了才改，没传就保留原值；新建且未传 startNodeId 时默认 "node_1"。
 *        章节管理已独立拆至 UpsertVisualNovelChapterTool（vn_chapter_upsert_tool）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 12:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.vn.VisualNovelLoader;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelData;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@ToolKitComponent(VisualNovelAuthoringToolKit.class)
@ConditionalOnExpression("${plug.vn.tool.authoring.enable:true} && ${plug.vn.tool.authoring.meta.upsert.enable:true}")
public class UpsertVisualNovelMetaTool implements ToolHandler {

    public static final String NAME = "vn_meta_upsert_tool";

    /** 新建小说且未指定首章起始节点时的默认值 */
    private static final String DEFAULT_START_NODE_ID = "node_1";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final VisualNovelLoader loader;

    public UpsertVisualNovelMetaTool(ObjectMapper objectMapper, VisualNovelLoader loader) {
        this.objectMapper = objectMapper;
        this.loader = loader;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        新建或更新视觉小说本体的元数据（data.json）：按 title（即小说目录名）定位，命中则更新，未命中则新建。
                        只更新传入的字段，未传的字段保留原值；新建且未传 startNodeId 时默认设为 "node_1"。
                        （如需新建或更新章节，请使用专门的 vn_chapter_upsert_tool）。""")
                .setRequired(List.of("title"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string", "小说标题（即目录名），必填。"),
                        new ToolRegister.Parameters("desc", "string", "小说简介（展示给玩家），可省略（省略则保留原值）。"),
                        new ToolRegister.Parameters("outline", "string", "小说总大纲（给 AI 看，不显示给玩家），可省略。"),
                        new ToolRegister.Parameters("coverImg", "string", "封面图文件名（相对 assets/），可省略。"),
                        new ToolRegister.Parameters("startNodeId", "string",
                                "第一章起始节点 ID，可省略；新建且省略时默认 \"node_1\"。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }
        if (!StringUtils.hasText(arguments.getTitle())) {
            throw new ToolExecutor.ToolExecuteException("参数 title 不能为空（小说标题即目录名）");
        }
        String title = arguments.getTitle().trim();

        VisualNovelData data;
        try {
            data = loader.loadData(title);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取视觉小说 `" + title + "` 失败: " + e.getMessage());
        }
        boolean created = data == null;
        if (created) {
            data = new VisualNovelData().setTitle(title);
        }
        if (arguments.getDesc() != null) {
            data.setDesc(arguments.getDesc());
        }
        if (arguments.getOutline() != null) {
            data.setOutline(arguments.getOutline());
        }
        if (arguments.getCoverImg() != null) {
            data.setCoverImg(arguments.getCoverImg());
        }
        if (arguments.getStartNodeId() != null) {
            data.setStartNodeId(arguments.getStartNodeId());
        }
        if (created && !StringUtils.hasText(data.getStartNodeId())) {
            data.setStartNodeId(DEFAULT_START_NODE_ID);
        }
        try {
            loader.saveData(title, data);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("保存视觉小说 `" + title + "` 失败: " + e.getMessage());
        }

        Path assetsDir = loader.getAssetsDir(title).toAbsolutePath().normalize();
        StringBuilder sb = new StringBuilder();
        sb.append("已保存视觉小说 `").append(title).append("`（").append(created ? "新建" : "更新").append("）。\n\n");
        sb.append("- 小说目录：`").append(assetsDir.getParent()).append("`\n");
        sb.append("- 图片资源目录：`").append(assetsDir).append("`\n");
        sb.append("- 首章起始节点：`").append(StringUtils.hasText(data.getStartNodeId()) ? data.getStartNodeId() : "（空）")
          .append("`\n");
        return new ToolExecutor.ToolExecuteResponse(NAME, sb.toString());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    private static class Arguments {
        private String title;
        private String desc;
        private String outline;
        private String coverImg;
        private String startNodeId;
    }
}
