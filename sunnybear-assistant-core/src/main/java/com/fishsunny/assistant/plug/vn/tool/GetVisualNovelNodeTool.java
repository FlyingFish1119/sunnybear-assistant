package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 导出一章的节点工作目录 —— 把整章节点摊成会话目录下的三个文件：
 *        nodes.json（可编辑，写回的权威快照）、HISTORY.md（剧情速览，只读）、README.md（节点编写指南）。
 *        template=true 时不查小说，固定把五类节点的最小模板生成到 vn/template/ 下。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 12:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.vn.VisualNovelLoader;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelChapter;
import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(VisualNovelAuthoringToolKit.class)
@ConditionalOnExpression("${plug.vn.tool.authoring.enable:true} && ${plug.vn.tool.authoring.node.get.enable:true}")
public class GetVisualNovelNodeTool implements ToolHandler {

    public static final String NAME = "vn_node_get_tool";

    /** 节点工作目录内的三个文件 */
    private static final String FILE_NODES = "nodes.json";
    private static final String FILE_HISTORY = "HISTORY.md";
    private static final String FILE_README = "README.md";

    /** 模板模式固定的输出目录（不占用小说命名空间） */
    private static final String TEMPLATE_DIR = "vn/template";

    /** 章节工作目录的父目录：vn/{title}/{chapterId}/ */
    private static final String VN_DIR = "vn";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final VisualNovelLoader loader;
    private final SessionFileManager sessionFileManager;

    public GetVisualNovelNodeTool(ObjectMapper objectMapper, VisualNovelLoader loader, SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.loader = loader;
        this.sessionFileManager = sessionFileManager;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        导出一章的节点工作目录。
                        传 title + chapterId 时，把该章节点摊成当前会话目录 vn/{title}/{chapterId}/ 下的三个文件并返回路径：
                        nodes.json（本章节点 JSON，按它编辑后用 vn_node_upsert_tool 全量写回）、
                        HISTORY.md（剥离 JSON 噪音的剧情速览，只读）、
                        README.md（节点编写指南：五类节点字段与示例、核心设计准则、assets 图片现状）。
                        传 template=true 时不查小说，固定把五类节点（normal / choice / branch / cg / chapter）
                        的最小模板生成到 vn/template/ 下。""")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string",
                                "小说标题（即目录名），template=true 时可省略。"),
                        new ToolRegister.Parameters("chapterId", "string",
                                "章节目录名，形如 \"1-章节目录结构\"，template=true 时可省略。"),
                        new ToolRegister.Parameters("template", "boolean",
                                "为 true 时生成五类节点模板到 vn/template/ 下，可省略（默认 false）。")
                ));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        String sessionId = requireSessionId(context);
        if (Boolean.TRUE.equals(arguments.getTemplate())) {
            return new ToolExecutor.ToolExecuteResponse(NAME, exportTemplate(sessionId));
        }

        if (!StringUtils.hasText(arguments.getTitle())) {
            throw new ToolExecutor.ToolExecuteException("参数 title 不能为空（小说标题即目录名）；只想看节点模板请传 template=true");
        }
        if (!StringUtils.hasText(arguments.getChapterId())) {
            throw new ToolExecutor.ToolExecuteException("参数 chapterId 不能为空（形如 \"1-章节目录结构\"）；只想看节点模板请传 template=true");
        }
        String title = arguments.getTitle().trim();
        String chapterId = arguments.getChapterId().trim();
        return new ToolExecutor.ToolExecuteResponse(NAME, exportChapter(sessionId, title, chapterId));
    }

    /** 取当前会话 ID；写会话文件目录必须具备，缺失即报错 */
    private String requireSessionId(Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        if (chatSession == null || !StringUtils.hasText(chatSession.getId())) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息，无法写入会话文件目录");
        }
        return chatSession.getId();
    }

    // ==================== 章节节点导出 ====================

    /** 读取整章节点并摊成 nodes.json / HISTORY.md / README.md 三个文件 */
    private String exportChapter(String sessionId, String title, String chapterId)
            throws ToolExecutor.ToolExecuteException {
        if (loader.loadData(title) == null) {
            throw new ToolExecutor.ToolExecuteException("未找到视觉小说 `" + title + "`。可用 `vn_meta_upsert_tool` 新建一部。");
        }
        VisualNovelChapter chapter;
        try {
            chapter = loader.loadChapter(title, chapterId);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取章节 `" + chapterId + "` 失败: " + e.getMessage());
        }
        if (chapter == null) {
            throw new ToolExecutor.ToolExecuteException("未找到章节目录 `" + chapterId + "`（小说 `" + title + "`）。"
                    + "可用章节：" + availableChapters(title));
        }

        Map<String, VisualNovelNode> nodes;
        List<String> assets;
        try {
            nodes = loader.loadNodes(title, chapterId);
            assets = loader.listAssets(title);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取章节内容失败: " + e.getMessage());
        }

        String dir = VN_DIR + "/" + SessionFileManager.sanitizeFileName(title)
                + "/" + SessionFileManager.sanitizeFileName(chapterId);
        writeFile(sessionId, dir + "/" + FILE_NODES,
                nodes.isEmpty() ? "{}" : VisualNovelAuthoringToolKit.toNodesJson(objectMapper, nodes));
        writeFile(sessionId, dir + "/" + FILE_HISTORY,
                VisualNovelAuthoringToolKit.buildHistoryMarkdown(title, chapterId, nodes));
        writeFile(sessionId, dir + "/" + FILE_README,
                VisualNovelAuthoringToolKit.buildReadmeMarkdown(title, chapterId, loader.getAssetsDir(title), assets));

        StringBuilder sb = new StringBuilder();
        sb.append("已导出章节 `").append(chapterId).append("`（小说 `").append(title).append("`，当前 ")
          .append(nodes.size()).append(" 个节点）的节点工作目录：`")
          .append(SessionFileManager.buildSessionMarker(dir)).append("/`\n\n");
        sb.append("- ").append(marker(dir, FILE_NODES)).append(" — 节点 JSON（编辑后全量写回，即本章权威快照）\n");
        sb.append("- ").append(marker(dir, FILE_HISTORY)).append(" — 剧情速览（只读参考，改了不生效）\n");
        sb.append("- ").append(marker(dir, FILE_README)).append(" — 节点编写指南（五类节点字段与示例 / 设计准则 / 图片资源现状）\n\n");
        sb.append("使用说明：先读 HISTORY.md 了解前情 → 用 `file_search_tool` 在 nodes.json 中定位节点 → ")
          .append("用 `file_edit_tool` / `file_write_tool` 编辑 → 用 `vn_node_upsert_tool` 传 nodes.json 路径写回。");
        if (nodes.isEmpty()) {
            sb.append("\n\n> 该章当前没有任何节点（nodes.json 为 `{}`）：可传 `template=true` 查看五类节点的最小模板，")
              .append("或直接看 README.md 里的全部节点示例。");
        }
        return sb.toString();
    }

    /** 该小说当前的章节目录名清单，用于报错时告诉调用方可用选项 */
    private String availableChapters(String title) throws ToolExecutor.ToolExecuteException {
        List<VisualNovelChapter> chapters;
        try {
            chapters = loader.listChapters(title);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取章节列表失败: " + e.getMessage());
        }
        if (chapters.isEmpty()) {
            return "（该小说还没有任何章节，可用 `vn_chapter_upsert_tool` 传入 title / order / chapterTitle 新建）";
        }
        List<String> ids = new ArrayList<>();
        for (VisualNovelChapter chapter : chapters) {
            ids.add("`" + chapter.buildChapterId() + "`");
        }
        return String.join("、", ids);
    }

    // ==================== 节点模板导出 ====================

    /** 生成五类节点模板到会话目录 vn/template/ 下 */
    private String exportTemplate(String sessionId) throws ToolExecutor.ToolExecuteException {
        String nodesJson = VisualNovelAuthoringToolKit.buildTemplateNodesJson();
        Map<String, VisualNovelNode> nodes;
        try {
            nodes = VisualNovelAuthoringToolKit.parseNodes(objectMapper, nodesJson);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("解析节点模板失败: " + e.getMessage());
        }
        writeFile(sessionId, TEMPLATE_DIR + "/" + FILE_NODES, nodesJson);
        writeFile(sessionId, TEMPLATE_DIR + "/" + FILE_HISTORY,
                VisualNovelAuthoringToolKit.buildHistoryMarkdown("（模板）", "（模板）", nodes));
        writeFile(sessionId, TEMPLATE_DIR + "/" + FILE_README,
                VisualNovelAuthoringToolKit.buildReadmeMarkdown("（模板）", "（模板）", null, List.of()));

        StringBuilder sb = new StringBuilder();
        sb.append("已生成视觉小说节点模板，目录：`")
          .append(SessionFileManager.buildSessionMarker(TEMPLATE_DIR)).append("/`\n\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_NODES))
          .append(" — 五类节点（normal / choice / branch / cg / chapter）的最小标准写法\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_HISTORY)).append(" — 上述模板节点的剧情速览（展示 HISTORY.md 长什么样）\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_README)).append(" — 节点编写指南全文\n\n");
        sb.append("注意：模板只是参考写法，写回真实章节请用 `vn_node_get_tool`（传 title + chapterId）导出的 nodes.json。");
        return sb.toString();
    }

    // ==================== 辅助 ====================

    private void writeFile(String sessionId, String relativePath, String content) throws ToolExecutor.ToolExecuteException {
        try {
            sessionFileManager.writeSessionText(sessionId, relativePath, content);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("写入会话文件 `" + relativePath + "` 失败: " + e.getMessage());
        }
    }

    /** 供模型使用的会话沙箱路径标记 */
    private String marker(String dir, String fileName) {
        return SessionFileManager.buildSessionMarker(dir + "/" + fileName);
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
        private String chapterId;
        private Boolean template;
    }
}
