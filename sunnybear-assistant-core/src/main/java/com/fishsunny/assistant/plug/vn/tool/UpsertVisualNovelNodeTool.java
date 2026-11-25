package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 写回一章的节点 —— 读会话目录里的 nodes.json，校验后整份覆盖落盘，并同步会话里的 HISTORY.md。
 *        写回是「全量覆盖」语义：文件就是本章的权威快照，文件里没有的节点会被删除。
 *        写回后出一份体检报告：节点分类计数、自然断点清单（指向不存在节点的引用）、
 *        章节跳转目标是否存在、引用了但 assets/ 下缺失的图片。
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
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelBranchNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChapterNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChoiceNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelCGNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelNormalNode;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ToolKitComponent(VisualNovelAuthoringToolKit.class)
@ConditionalOnExpression("${plug.vn.tool.authoring.enable:true} && ${plug.vn.tool.authoring.node.upsert.enable:true}")
public class UpsertVisualNovelNodeTool implements ToolHandler {

    public static final String NAME = "vn_node_upsert_tool";

    private static final String FILE_HISTORY = "HISTORY.md";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final VisualNovelLoader loader;
    private final SessionFileManager sessionFileManager;

    public UpsertVisualNovelNodeTool(ObjectMapper objectMapper, VisualNovelLoader loader, SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.loader = loader;
        this.sessionFileManager = sessionFileManager;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        写回一章的节点：读会话里的 nodes.json，校验后整份覆盖落盘（全量覆盖，文件里没有的节点会被删除），
                        同时把同级 HISTORY.md 一并刷新，保持会话文件与落盘数据同步。
                        返回写回体检报告：节点总数与五类节点各自数量、自然断点清单
                        （nextNodeId / options[].targetNodeId / branches[].targetNodeId 指向本章不存在的节点，
                        即游戏会停下等待续写的位置）、chapter 节点的目标章节是否存在、
                        以及引用了但 assets/ 下尚不存在的图片文件名。""")
                .setRequired(List.of("title", "chapterId", "nodesPath"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("title", "string", "小说标题（即目录名），必填。"),
                        new ToolRegister.Parameters("chapterId", "string",
                                "目标章节目录名，形如 \"1-章节目录结构\"，必填。"),
                        new ToolRegister.Parameters("nodesPath", "string",
                                "会话沙箱中 nodes.json 的路径，必填，如 sessionId:vn/{title}/{chapterId}/nodes.json。")
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
        if (!StringUtils.hasText(arguments.getTitle())) {
            throw new ToolExecutor.ToolExecuteException("参数 title 不能为空（小说标题即目录名）");
        }
        if (!StringUtils.hasText(arguments.getChapterId())) {
            throw new ToolExecutor.ToolExecuteException("参数 chapterId 不能为空（形如 \"1-章节目录结构\"）");
        }
        if (!StringUtils.hasText(arguments.getNodesPath())) {
            throw new ToolExecutor.ToolExecuteException("参数 nodesPath 不能为空（如 sessionId:vn/{title}/{chapterId}/nodes.json）");
        }
        String title = arguments.getTitle().trim();
        String chapterId = arguments.getChapterId().trim();

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();

        Path nodesFile = resolveNodesFile(arguments.getNodesPath(), sessionId);
        LinkedHashMap<String, VisualNovelNode> nodes = readNodes(nodesFile, arguments.getNodesPath());
        validateNodes(nodes);
        requireChapter(title, chapterId);

        try {
            loader.saveNodes(title, chapterId, nodes);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("写回节点失败: " + e.getMessage());
        }

        String historyMarkdown = VisualNovelAuthoringToolKit.buildHistoryMarkdown(title, chapterId, nodes);
        Path historyFile = nodesFile.getParent().resolve(FILE_HISTORY);
        try {
            Files.writeString(historyFile, historyMarkdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("节点已写回，但同步会话里的 HISTORY.md 失败: " + e.getMessage());
        }

        return new ToolExecutor.ToolExecuteResponse(NAME,
                buildReport(title, chapterId, nodes, nodesFile, historyFile));
    }

    // ==================== 读取与校验 ====================

    /** 解析会话沙箱路径（支持 sessionId: 前缀），并确认文件存在 */
    private Path resolveNodesFile(String path, String sessionId) throws ToolExecutor.ToolExecuteException {
        Path file;
        try {
            file = sessionFileManager.resolveToolPath(path.trim(), sessionId).path();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("路径不合法: " + path + "（" + e.getMessage() + "）");
        }
        if (!Files.isRegularFile(file)) {
            throw new ToolExecutor.ToolExecuteException("未找到 nodes.json: " + path
                    + "（请先用 `vn_node_get_tool` 传 title + chapterId 把该章节点导出到会话目录）");
        }
        return file;
    }

    /** 读文件并解析成有序节点表；JSON 错误直接把 Jackson 的原因带出去 */
    private LinkedHashMap<String, VisualNovelNode> readNodes(Path file, String path) throws ToolExecutor.ToolExecuteException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("读取 nodes.json 失败: " + path + "（" + e.getMessage() + "）");
        }
        if (!StringUtils.hasText(json)) {
            throw new ToolExecutor.ToolExecuteException("nodes.json 内容为空: " + path + "（写空章节请填 {}）");
        }
        try {
            return VisualNovelAuthoringToolKit.parseNodes(objectMapper, json);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "nodes.json 不是合法的节点 JSON: " + e.getMessage()
                            + "。要求顶层是 {节点ID: 节点对象} 的对象，每个节点对象必须带 type"
                            + "（normal / cg / choice / branch / chapter）之一。");
        }
    }

    /** 节点基本合法性：ID 非空、节点非 null、各类型的核心字段齐备 */
    private void validateNodes(Map<String, VisualNovelNode> nodes) throws ToolExecutor.ToolExecuteException {
        for (Map.Entry<String, VisualNovelNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            if (!StringUtils.hasText(nodeId)) {
                throw new ToolExecutor.ToolExecuteException("nodes.json 中存在空的节点 ID，请给每个节点一个非空 key");
            }
            VisualNovelNode node = entry.getValue();
            if (node == null) {
                throw new ToolExecutor.ToolExecuteException("节点 `" + nodeId + "` 的内容为 null，请补全或删掉该键");
            }
            if (node instanceof VisualNovelChoiceNode choice
                    && (choice.getOptions() == null || choice.getOptions().isEmpty())) {
                throw new ToolExecutor.ToolExecuteException("choice 节点 `" + nodeId + "` 缺少 options，至少要有一个选项");
            }
            if (node instanceof VisualNovelBranchNode branch) {
                if (!StringUtils.hasText(branch.getDefaultTargetNodeId())) {
                    throw new ToolExecutor.ToolExecuteException("branch 节点 `" + nodeId + "` 缺少 defaultTargetNodeId（必须指定默认兜底目标节点，防止无分支命中时卡死）");
                }
                if (branch.getBranches() == null || branch.getBranches().isEmpty()) {
                    throw new ToolExecutor.ToolExecuteException("branch 节点 `" + nodeId + "` 缺少 branches，至少要有一个条件分支");
                }
            }
            if (node instanceof VisualNovelChapterNode chapter
                    && !StringUtils.hasText(chapter.getTargetChapter())) {
                throw new ToolExecutor.ToolExecuteException("chapter 节点 `" + nodeId + "` 缺少 targetChapter");
            }
        }
    }

    /** 章节必须已存在：saveNodes 会自动建目录，放任写错 chapterId 会留下一个幽灵章节 */
    private void requireChapter(String title, String chapterId) throws ToolExecutor.ToolExecuteException {
        VisualNovelChapter chapter;
        try {
            if (loader.loadData(title) == null) {
                throw new ToolExecutor.ToolExecuteException("未找到视觉小说 `" + title + "`"
                        + "。可用 `vn_meta_upsert_tool` 新建，或先用 `vn_meta_get_tool` 查看已有小说。");
            }
            chapter = loader.loadChapter(title, chapterId);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取章节 `" + chapterId + "` 失败: " + e.getMessage());
        }
        if (chapter == null) {
            List<VisualNovelChapter> chapters = loader.listChapters(title);
            List<String> ids = new ArrayList<>();
            for (VisualNovelChapter item : chapters) {
                ids.add("`" + item.buildChapterId() + "`");
            }
            throw new ToolExecutor.ToolExecuteException("未找到章节目录 `" + chapterId + "`（小说 `" + title + "`）。"
                    + (ids.isEmpty()
                    ? "该小说还没有任何章节，可用 `vn_chapter_upsert_tool` 传入 title / order / chapterTitle 新建。"
                    : "可用章节：" + String.join("、", ids)));
        }
    }

    // ==================== 体检报告 ====================

    private String buildReport(String title, String chapterId, Map<String, VisualNovelNode> nodes,
                               Path nodesFile, Path historyFile) {
        int normal = 0;
        int cg = 0;
        int choice = 0;
        int branch = 0;
        int chapter = 0;
        for (VisualNovelNode node : nodes.values()) {
            if (node instanceof VisualNovelNormalNode) {
                normal++;
            } else if (node instanceof VisualNovelCGNode) {
                cg++;
            } else if (node instanceof VisualNovelChoiceNode) {
                choice++;
            } else if (node instanceof VisualNovelBranchNode) {
                branch++;
            } else if (node instanceof VisualNovelChapterNode) {
                chapter++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已全量写回 `").append(title).append("` / `").append(chapterId).append("` 的 nodes.json，共 ")
          .append(nodes.size()).append(" 个节点：normal ").append(normal)
          .append(" ｜ cg ").append(cg)
          .append(" ｜ choice ").append(choice)
          .append(" ｜ branch ").append(branch)
          .append(" ｜ chapter ").append(chapter).append("\n\n");
        sb.append("- 落盘文件：`").append(nodesFile.toAbsolutePath().normalize()).append("`\n");
        sb.append("- 已同步会话内的 `").append(historyFile.getFileName()).append("`：`")
          .append(SessionFileManager.buildSessionMarker(relativizeToSession(historyFile))).append("`\n\n");

        appendBreakpoints(sb, nodes);
        appendChapterTargets(sb, title, nodes);
        appendMissingAssets(sb, title, nodes);
        return sb.toString();
    }

    /** 自然断点：指向本章不存在节点的引用，游戏运行到此处会停下等续写 */
    private void appendBreakpoints(StringBuilder sb, Map<String, VisualNovelNode> nodes) {
        List<String> lines = new ArrayList<>();
        Set<String> ids = nodes.keySet();
        for (Map.Entry<String, VisualNovelNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            VisualNovelNode node = entry.getValue();
            if (node instanceof VisualNovelChoiceNode choice && choice.getOptions() != null) {
                int index = 1;
                for (VisualNovelChoiceNode.Options option : choice.getOptions()) {
                    if (option != null && StringUtils.hasText(option.getTargetNodeId())
                            && !ids.contains(option.getTargetNodeId())) {
                        lines.add("`" + nodeId + "` 选项 " + index + " -> `" + option.getTargetNodeId() + "`");
                    }
                    index++;
                }
                continue;
            }
            if (node instanceof VisualNovelBranchNode branchNode) {
                if (branchNode.getBranches() != null) {
                    int index = 1;
                    for (VisualNovelBranchNode.Branch item : branchNode.getBranches()) {
                        if (item != null && StringUtils.hasText(item.getTargetNodeId())
                                && !ids.contains(item.getTargetNodeId())) {
                            lines.add("`" + nodeId + "` 分支 " + index + " -> `" + item.getTargetNodeId() + "`");
                        }
                        index++;
                    }
                }
                if (StringUtils.hasText(branchNode.getDefaultTargetNodeId())
                        && !ids.contains(branchNode.getDefaultTargetNodeId())) {
                    lines.add("`" + nodeId + "` 默认兜底 defaultTargetNodeId -> `" + branchNode.getDefaultTargetNodeId() + "`");
                }
                continue;
            }
            if (StringUtils.hasText(node.getNextNodeId()) && !ids.contains(node.getNextNodeId())) {
                lines.add("`" + nodeId + "` nextNodeId -> `" + node.getNextNodeId() + "`");
            }
        }

        sb.append("### 自然断点（").append(lines.size()).append("）\n\n");
        if (lines.isEmpty()) {
            sb.append("无：所有引用目标都在本章内，剧情在本章内闭合。\n\n");
            return;
        }
        sb.append("以下目标节点在本章不存在，游戏运行到此处会停下等待续写，请核对是否符合预期：\n");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        sb.append("\n");
    }

    /** chapter 节点的目标章节是否存在 */
    private void appendChapterTargets(StringBuilder sb, String title, Map<String, VisualNovelNode> nodes) {
        List<String> lines = new ArrayList<>();
        List<VisualNovelChapter> chapters = loader.listChapters(title);
        for (Map.Entry<String, VisualNovelNode> entry : nodes.entrySet()) {
            if (!(entry.getValue() instanceof VisualNovelChapterNode chapterNode)) {
                continue;
            }
            String targetChapter = chapterNode.getTargetChapter();
            boolean exists = false;
            for (VisualNovelChapter item : chapters) {
                if (item.buildChapterId().equals(targetChapter)) {
                    exists = true;
                    break;
                }
            }
            lines.add("`" + entry.getKey() + "` -> 章节 `" + targetChapter + "`："
                    + (exists ? "已存在" : "不存在（该章节需先创建）"));
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("### 章节跳转\n\n");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        sb.append("\n");
    }

    /** 引用了但 assets/ 下不存在的图片 */
    private void appendMissingAssets(StringBuilder sb, String title, Map<String, VisualNovelNode> nodes) {
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (Map.Entry<String, VisualNovelNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            if (entry.getValue() instanceof VisualNovelNormalNode normal) {
                addAsset(refs, normal.getBackground(), "normal `" + nodeId + "`.background");
                if (normal.getCharacters() != null) {
                    for (VisualNovelNormalNode.Character character : normal.getCharacters()) {
                        if (character != null) {
                            addAsset(refs, character.getSprite(), "normal `" + nodeId + "`.characters[].sprite");
                        }
                    }
                }
            } else if (entry.getValue() instanceof VisualNovelCGNode cg) {
                addAsset(refs, cg.getImg(), "cg `" + nodeId + "`.img");
            }
        }

        Set<String> existing = new LinkedHashSet<>(loader.listAssets(title));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : refs.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                missing.add("`" + entry.getKey() + "`（" + String.join("、", entry.getValue()) + "）");
            }
        }

        Path assetsDir = loader.getAssetsDir(title).toAbsolutePath().normalize();
        sb.append("### 缺失图片资源（").append(missing.size()).append("）\n\n");
        if (missing.isEmpty()) {
            sb.append("无：本章引用的图片都已在 `").append(assetsDir).append("` 下存在。\n");
            return;
        }
        sb.append("以下图片在 `").append(assetsDir).append("` 下尚不存在，需要补图或改名：\n");
        for (String line : missing) {
            sb.append("- ").append(line).append("\n");
        }
    }

    private void addAsset(Map<String, List<String>> refs, String fileName, String source) {
        if (!StringUtils.hasText(fileName)) {
            return;
        }
        refs.computeIfAbsent(fileName, key -> new ArrayList<>()).add(source);
    }

    /** 会话内文件的相对路径（用于拼 sessionId: 标记）；不在会话目录里时退回文件名 */
    private String relativizeToSession(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        String normalized = absolute.toString().replace('\\', '/');
        int index = normalized.indexOf("/file/");
        return index < 0 ? file.getFileName().toString() : normalized.substring(index + "/file/".length());
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
        private String nodesPath;
    }
}
