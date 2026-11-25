package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 新建或更新视觉小说的章节（{order}-{chapterTitle}/chapter.md）。
 *        职责与小说本体元数据（UpsertVisualNovelMetaTool）完全分离：
 *        只接收所属小说 title、章节序号 order、章节标题 chapterTitle 与章节核心大纲 content。
 *        首次创建章节时会自动初始化该章节目录下的空 nodes.json（{}）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 12:05
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.vn.VisualNovelLoader;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelChapter;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(VisualNovelAuthoringToolKit.class)
@ConditionalOnExpression("${plug.vn.tool.authoring.enable:true} && ${plug.vn.tool.authoring.chapter.upsert.enable:true}")
public class UpsertVisualNovelChapterTool implements ToolHandler {

    public static final String NAME = "vn_chapter_upsert_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final VisualNovelLoader loader;

    public UpsertVisualNovelChapterTool(ObjectMapper objectMapper, VisualNovelLoader loader) {
        this.objectMapper = objectMapper;
        this.loader = loader;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        新建或更新视觉小说的单个章节（{order}-{chapterTitle}/chapter.md）。
                        章节目录名按 "{order}-{chapterTitle}" 自动拼接（如 order=1, chapterTitle="深夜书房" -> "1-深夜书房"）。
                        首次创建该章节时会自动在章节目录下初始化空的 nodes.json（{}）；
                        若章节已存在且未传 content，则保留原有的 chapter.md 内容。""")
                .setRequired(List.of("title", "order", "chapterTitle"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string", "所属视觉小说标题（即小说目录名），必填。"),
                        new ToolRegister.Parameters("order", "integer", "章节序号（从 1 开始，如 1、2），必填。"),
                        new ToolRegister.Parameters("chapterTitle", "string", "章节标题（不含序号前缀），必填。"),
                        new ToolRegister.Parameters("content", "string",
                                "章节核心大意与流程梗概（写入 chapter.md，供 AI 快速理解本章脉络，不显示给玩家），可省略。")
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
            throw new ToolExecutor.ToolExecuteException("参数 title 不能为空（所属小说标题即目录名）");
        }
        if (arguments.getOrder() == null || arguments.getOrder() <= 0) {
            throw new ToolExecutor.ToolExecuteException("参数 order 必须为正整数（从 1 开始）");
        }
        if (!StringUtils.hasText(arguments.getChapterTitle())) {
            throw new ToolExecutor.ToolExecuteException("参数 chapterTitle 不能为空（章节标题，不含序号前缀）");
        }

        String title = arguments.getTitle().trim();
        if (loader.loadData(title) == null) {
            throw new ToolExecutor.ToolExecuteException("未找到视觉小说 `" + title + "`，请先用 `vn_meta_upsert_tool` 创建小说。");
        }

        VisualNovelChapter chapter = new VisualNovelChapter()
                .setOrder(arguments.getOrder())
                .setTitle(arguments.getChapterTitle().trim());
        String chapterId = chapter.buildChapterId();

        VisualNovelChapter existing;
        try {
            existing = loader.loadChapter(title, chapterId);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取章节 `" + chapterId + "` 失败: " + e.getMessage());
        }

        String statusNote;
        if (existing != null && arguments.getContent() == null) {
            chapter.setContent(existing.getContent());
            statusNote = "已存在，chapter.md 保持原样";
        } else {
            chapter.setContent(arguments.getContent());
            statusNote = existing != null ? "已更新 chapter.md" : "已新建章节目录并初始化 nodes.json";
        }

        try {
            loader.saveChapter(title, chapter);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("保存章节 `" + chapterId + "` 失败: " + e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已保存章节 `").append(chapterId).append("`（所属小说：`").append(title).append("`）—— ").append(statusNote).append("。\n\n");
        sb.append("- 章节 ID：`").append(chapterId).append("`\n");
        sb.append("- 下一步：可调用 `vn_node_get_tool`（传入 title=`").append(title)
          .append("`, chapterId=`").append(chapterId).append("`）导出该章节的节点工作区进行编写。");
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
        private Integer order;
        private String chapterTitle;
        private String content;
    }
}
