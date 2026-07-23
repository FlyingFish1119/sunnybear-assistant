package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件列表工具 - 列出目录中的文件和子目录，支持递归、过滤、排序和分页
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

/**
 * 文件列表工具
 * 支持列出指定目录下的文件和子目录，具备以下能力：
 * - 递归列出（可控制深度）
 * - glob 模式过滤（如 *.java、*.{java,kt}）
 * - 按名称/大小/修改时间排序
 * - 分页（offset + limit）
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-list.enable:true}")
public class FileListTool implements ToolHandler {

    public static final String NAME = "file_list_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** 默认最大返回条目数 */
    private static final int DEFAULT_LIMIT = 200;

    /** 最大递归深度 */
    private static final int MAX_DEPTH = 10;

    /** 单次请求最大返回条目数上限 */
    private static final int MAX_LIMIT = 1000;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FileListTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("列出目录下的文件和子目录，支持递归深度、glob 过滤和分页。用于浏览项目结构、查找文件。")
                .setRequired(List.of("path"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "目录路径，例如 D:\\projects 或 /home/user"),
                        new ToolRegister.Parameters("depth", "integer", "遍历深度：1=仅当前目录（默认），2=含一级子目录，最大 " + MAX_DEPTH),
                        new ToolRegister.Parameters("filter", "string", "glob 文件名过滤，例如 *.java、*.{java,kt}，不指定则列出所有"),
                        new ToolRegister.Parameters("offset", "integer", "跳过前 N 个条目（从 0 开始），默认 0"),
                        new ToolRegister.Parameters("limit", "integer", "最大返回条目数，默认 " + DEFAULT_LIMIT)
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        if (!StringUtils.hasText(arguments.getPath())) {
            throw new ToolExecutor.ToolExecuteException("参数 path 不能为空");
        }

        Path dirPath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

        if (!Files.exists(dirPath)) {
            throw new ToolExecutor.ToolExecuteException("路径不存在: " + dirPath);
        }
        if (!Files.isDirectory(dirPath)) {
            throw new ToolExecutor.ToolExecuteException("路径指向的不是目录: " + dirPath);
        }
        if (!Files.isReadable(dirPath)) {
            throw new ToolExecutor.ToolExecuteException("目录不可读: " + dirPath);
        }

        // 解析参数默认值
        int depth = arguments.getDepth() == null ? 1 : Math.min(Math.max(arguments.getDepth(), 1), MAX_DEPTH);

        String filter = arguments.getFilter();

        int offset = arguments.getOffset() == null ? 0 : Math.max(arguments.getOffset(), 0);
        int limit = arguments.getLimit() == null ? DEFAULT_LIMIT : Math.min(Math.max(arguments.getLimit(), 1), MAX_LIMIT);

        try {
            // 构建文件树
            TreeNode root = collectTree(dirPath, dirPath, depth, filter);

            // 排序（目录优先，按名称升序）
            sortTree(root);

            // 统计总条目数（排除根节点自身）
            int totalCount = countNodes(root) - 1;

            // 构建输出头部
            StringBuilder sb = new StringBuilder();
            sb.append("目录[").append(dirPath).append("]");
            if (depth > 1) {
                sb.append(" 递归深度:").append(depth);
            }
            if (StringUtils.hasText(filter)) {
                sb.append(" 过滤:").append(filter);
            }
            sb.append(" 共 ").append(totalCount).append(" 个条目");

            if (totalCount == 0) {
                sb.append("\n");
                return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
            }

            sb.append("\n\n```file-list\n").append(dirPath).append("\\\n");

            // 渲染树
            int[] rendered = new int[1];
            int skip = offset;

            List<TreeNode> children = root.children;
            for (int i = 0; i < children.size() && rendered[0] < limit; i++) {
                if (skip > 0) {
                    skip--;
                    continue;
                }
                TreeNode child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                renderNode(child, "", isLast, sb, rendered, limit);
            }
            sb.append("```");

            if (totalCount > rendered[0] + offset) {
                sb.append("\n... 还有 ").append(totalCount - rendered[0] - offset)
                        .append(" 个条目未显示");
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("目录读取失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("文件列表获取异常: " + e.getMessage());
        }
    }

    // ======================== 树构建 / 排序 / 渲染 ========================

    /**
     * 递归构建文件树。
     * 当 filter 生效时，不包含匹配文件的目录会被自动剪除（深度耗尽处除外）。
     */
    private TreeNode collectTree(Path rootDir, Path currentDir, int remainingDepth, String filter) throws IOException {
        TreeNode node = new TreeNode();
        node.name = currentDir.getFileName() != null ? currentDir.getFileName().toString() : currentDir.toString();
        node.isDir = true;

        boolean hasFilter = StringUtils.hasText(filter);
        PathMatcher matcher = null;
        if (hasFilter) {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + filter);
        }

        if (remainingDepth <= 0) {
            node.atDepthLimit = true;
            return node;
        }

        try (Stream<Path> stream = Files.list(currentDir)) {
            List<Path> children = stream.sorted().toList();
            for (Path child : children) {
                String fileName = child.getFileName().toString();
                if (fileName.startsWith(".")) continue;

                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);

                    if (attrs.isDirectory()) {
                        TreeNode childNode = collectTree(rootDir, child, remainingDepth - 1, filter);
                        // 有 filter 时：只保留深度耗尽或内部有匹配内容的目录
                        // 无 filter 时：目录始终保留（包括空目录）
                        if (!hasFilter || childNode.atDepthLimit || !childNode.children.isEmpty()) {
                            node.children.add(childNode);
                        }
                    } else {
                        if (hasFilter && !matcher.matches(child.getFileName())) continue;
                        TreeNode childNode = new TreeNode();
                        childNode.name = fileName;
                        childNode.isDir = false;
                        childNode.size = attrs.size();
                        childNode.modifiedTime = attrs.lastModifiedTime();
                        node.children.add(childNode);
                    }
                } catch (IOException ignored) {
                    // 跳过无法访问的文件
                }
            }
        }
        return node;
    }

    /**
     * 递归排序树节点：目录优先，按名称升序
     */
    private void sortTree(TreeNode node) {
        if (node.children.isEmpty()) return;

        Comparator<TreeNode> comparator = Comparator
                .comparing(TreeNode::isDir).reversed()
                .thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER);

        node.children.sort(comparator);

        for (TreeNode child : node.children) {
            if (child.isDir) {
                sortTree(child);
            }
        }
    }

    /** DFS 统计节点总数（含自身） */
    private int countNodes(TreeNode node) {
        int count = 1;
        for (TreeNode child : node.children) {
            count += countNodes(child);
        }
        return count;
    }

    /** 递归渲染树节点 */
    private void renderNode(TreeNode node, String prefix, boolean isLast,
                            StringBuilder sb, int[] rendered, int maxRender) {
        if (rendered[0] >= maxRender) return;

        String connector = isLast ? "└── " : "├── ";
        sb.append(prefix).append(connector).append(node.name);

        if (node.isDir) {
            sb.append("/\n");
            rendered[0]++;
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            List<TreeNode> children = node.children;
            for (int i = 0; i < children.size() && rendered[0] < maxRender; i++) {
                renderNode(children.get(i), childPrefix, i == children.size() - 1, sb, rendered, maxRender);
            }
        } else {
            sb.append(" (").append(formatNodeSize(node.size)).append(", ").append(formatNodeTime(node.modifiedTime)).append(")\n");
            rendered[0]++;
        }
    }

    private static String formatNodeSize(long size) {
        if (size < 0) return "?";
        return ToolKit.formatSize(size);
    }

    private static String formatNodeTime(FileTime time) {
        if (time == null || time.toMillis() == 0) return "未知";
        return FORMATTER.format(Instant.ofEpochMilli(time.toMillis()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    // ======================== 内部类 ========================

    @Data
    private static class Arguments {
        private String path;
        private Integer depth;
        private String filter;
        private Integer offset;
        private Integer limit;
    }

    @Data
    @Accessors(chain = true)
    private static class TreeNode {
        String name;
        boolean isDir;
        long size = -1;
        FileTime modifiedTime;
        /** 为 true 表示递归深度耗尽，未探索子目录内容，剪枝时应保留 */
        boolean atDepthLimit;
        List<TreeNode> children = new ArrayList<>();
    }
}
