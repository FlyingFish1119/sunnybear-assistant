package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 查看视觉小说 —— 不传 title 时列出全部小说；传入 title 时输出该小说的完整结构：
 *        基础信息、总大纲、章节列表（含 chapter.md 梗概全文）、图片资源、存档列表。
 *        只读，不做任何写盘。
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
import com.fishsunny.assistant.plug.vn.entity.VisualNovelChapter;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelData;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@ToolKitComponent(VisualNovelAuthoringToolKit.class)
@ConditionalOnExpression("${plug.vn.tool.authoring.enable:true} && ${plug.vn.tool.authoring.meta.get.enable:true}")
public class GetVisualNovelMetaTool implements ToolHandler {

    public static final String NAME = "vn_meta_get_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final VisualNovelLoader loader;

    public GetVisualNovelMetaTool(ObjectMapper objectMapper, VisualNovelLoader loader) {
        this.objectMapper = objectMapper;
        this.loader = loader;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查看视觉小说。
                        不传 title 时列出全部视觉小说（标题、简介、首章起始节点、章节数、图片资源数）；
                        传入 title 时输出该小说的结构：基础信息（简介 / 封面图 / 首章起始节点 / assets 目录路径）、
                        总大纲、章节列表（chapterId、各章节点总数、chapter.md 梗概全文）、
                        已有图片文件名、已有存档列表。只读，不修改任何文件。""")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string",
                                "小说标题（即目录名），可省略。省略时只列出全部小说。")
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
            return new ToolExecutor.ToolExecuteResponse(NAME, listNovels());
        }
        String title = arguments.getTitle().trim();
        VisualNovelData data = loadedOrFail(title);
        return new ToolExecutor.ToolExecuteResponse(NAME, describeNovel(title, data));
    }

    /** 不传 title 时列出全部小说 */
    private String listNovels() {
        List<String> titles = loader.listNovels();
        if (titles.isEmpty()) {
            return "当前还没有任何视觉小说。可用 `vn_meta_upsert_tool` 传入 title 新建一部。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(titles.size()).append(" 部视觉小说：\n\n");
        for (String title : titles) {
            VisualNovelData data = loader.loadData(title);
            List<VisualNovelChapter> chapters = loader.listChapters(title);
            int assets = loader.listAssets(title).size();
            sb.append("- **").append(title).append("**")
              .append("：").append(data == null ? "(缺少 data.json)" : summary(data.getDesc())).append("\n");
            sb.append("  - 首章起始节点：").append(data == null ? "（空）" : "`" + value(data.getStartNodeId()) + "`")
              .append(" ｜ 章节数：").append(chapters.size())
              .append(" ｜ 图片资源：").append(assets).append("\n");
        }
        return sb.toString();
    }

    /** 传入 title 时输出完整结构 */
    private String describeNovel(String title, VisualNovelData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("- 简介：").append(summary(data.getDesc())).append("\n");
        sb.append("- 封面图：").append(data.getCoverImg() == null ? "（未设置）" : "`" + data.getCoverImg() + "`").append("\n");
        sb.append("- 首章起始节点：`").append(value(data.getStartNodeId())).append("`\n");
        sb.append("- 图片资源目录：`").append(assetsDir(title)).append("`\n\n");

        sb.append("## 总大纲\n\n");
        sb.append(StringUtils.hasText(data.getOutline()) ? data.getOutline() : "（未填写）").append("\n\n");

        List<VisualNovelChapter> chapters = loader.listChapters(title);
        sb.append("## 章节（").append(chapters.size()).append("）\n\n");
        if (chapters.isEmpty()) {
            sb.append("（还没有任何章节，可用 `vn_chapter_upsert_tool` 传入 title / order / chapterTitle / content 新建）\n\n");
        }
        for (VisualNovelChapter chapter : chapters) {
            String chapterId = chapter.buildChapterId();
            int nodes = loader.loadNodes(title, chapterId).size();
            sb.append("### `").append(chapterId).append("`（节点 ").append(nodes).append(" 个）\n\n");
            sb.append("```markdown\n")
              .append(StringUtils.hasText(chapter.getContent()) ? chapter.getContent() : "（chapter.md 为空）")
              .append("\n```\n\n");
        }

        List<String> assets = loader.listAssets(title);
        sb.append("## 图片资源（").append(assets.size()).append("）\n\n");
        sb.append(assets.isEmpty() ? "（`assets/` 下暂无图片）\n\n" : "- " + String.join("\n- ", assets) + "\n\n");

        List<String> saves = loader.listSaves(title);
        sb.append("## 存档（").append(saves.size()).append("）\n\n");
        sb.append(saves.isEmpty() ? "（暂无存档）\n" : "- " + String.join("\n- ", saves) + "\n");
        return sb.toString();
    }

    // ==================== 辅助 ====================

    private VisualNovelData loadedOrFail(String title) throws ToolExecutor.ToolExecuteException {
        VisualNovelData data;
        try {
            data = loader.loadData(title);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取视觉小说 `" + title + "` 失败: " + e.getMessage());
        }
        if (data == null) {
            List<String> titles = loader.listNovels();
            throw new ToolExecutor.ToolExecuteException("未找到视觉小说 `" + title + "`。"
                    + (titles.isEmpty()
                    ? "当前还没有任何视觉小说，可用 `vn_meta_upsert_tool` 新建。"
                    : "可用的小说：" + String.join("、", titles)));
        }
        return data;
    }

    private String assetsDir(String title) {
        Path dir = loader.getAssetsDir(title);
        return dir.toAbsolutePath().normalize().toString();
    }

    /** 简介压成一行，过长的截断，避免列表被大段文本撑爆 */
    private String summary(String desc) {
        if (!StringUtils.hasText(desc)) {
            return "（无简介）";
        }
        String text = desc.replaceAll("\\s+", " ");
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private String value(String text) {
        return StringUtils.hasText(text) ? text : "（空）";
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
    }
}
