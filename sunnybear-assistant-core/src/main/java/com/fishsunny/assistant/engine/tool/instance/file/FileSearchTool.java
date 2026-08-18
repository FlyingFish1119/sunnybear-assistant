package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件内容搜索工具 - 在目录中递归搜索文件内容，支持正则匹配、glob 过滤和分页
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件内容搜索工具
 * 在指定目录下递归搜索文件内容，支持正则表达式匹配、glob 文件过滤和结果分页。
 * 用于根据文字内容查找相关文件。
 */
@Slf4j
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-search.enable:true}")
public class FileSearchTool implements ToolHandler {

    public static final String NAME = "file_search_tool";

    /** 默认最大返回匹配数 */
    private static final int DEFAULT_MAX_RESULTS = 50;

    /** 单次最大返回匹配数 */
    private static final int MAX_RESULTS_LIMIT = 500;

    /** 最大递归深度 */
    private static final int MAX_DEPTH = 10;

    /** 上下文行数上限 */
    private static final int MAX_CONTEXT_LINES = 5;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FileSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("搜索文件内容的首选工具（比执行 findstr/grep 命令更安全、无需用户确认、无输出限制）。支持正则匹配和 glob 文件过滤，根据文字/关键词快速查找相关文件。返回匹配的文件路径、行号和内容，可显示上下文行。" +
                        "注意：永远不要尝试从根目录开始搜索，这几乎一定会超时。")
                .setRequired(List.of("path", "pattern"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "搜索的起始目录路径，例如 D:\\projects 或 /home/user"),
                        new ToolRegister.Parameters("pattern", "string", "搜索的文字或正则表达式。默认作为普通文本匹配（非正则），如需使用正则请设置 useRegex=true"),
                        new ToolRegister.Parameters("useRegex", "boolean", "是否将 pattern 作为正则表达式匹配，默认 false（普通文本匹配）"),
                        new ToolRegister.Parameters("filter", "string", "glob 文件名过滤，例如 *.java、*.{java,kt}，不指定则搜索所有文本文件"),
                        new ToolRegister.Parameters("depth", "integer", "递归深度，默认 " + MAX_DEPTH + "（递归搜索所有子目录）。设为 1 仅搜索当前目录"),
                        new ToolRegister.Parameters("caseSensitive", "boolean", "是否区分大小写，默认 false（不区分大小写）"),
                        new ToolRegister.Parameters("contextLines", "integer", "匹配行前后各显示多少行上下文，默认 0（仅显示匹配行），最大 " + MAX_CONTEXT_LINES),
                        new ToolRegister.Parameters("maxResults", "integer", "最大返回匹配数，默认 " + DEFAULT_MAX_RESULTS + "，上限 " + MAX_RESULTS_LIMIT)
                )).setTimeoutMs(30000);
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
        if (!StringUtils.hasText(arguments.getPattern())) {
            throw new ToolExecutor.ToolExecuteException("参数 pattern 不能为空");
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
        int depth = arguments.getDepth() == null ? MAX_DEPTH : Math.min(Math.max(arguments.getDepth(), 1), MAX_DEPTH);
        int maxResults = arguments.getMaxResults() == null ? DEFAULT_MAX_RESULTS : Math.min(Math.max(arguments.getMaxResults(), 1), MAX_RESULTS_LIMIT);
        int contextLines = arguments.getContextLines() == null ? 0 : Math.min(Math.max(arguments.getContextLines(), 0), MAX_CONTEXT_LINES);
        boolean caseSensitive = arguments.getCaseSensitive() != null && arguments.getCaseSensitive();
        boolean useRegex = arguments.getUseRegex() != null && arguments.getUseRegex();
        String filter = arguments.getFilter();

        // 编译搜索模式
        Pattern pattern;
        try {
            if (useRegex) {
                pattern = Pattern.compile(arguments.getPattern(),
                        caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } else {
                pattern = Pattern.compile(Pattern.quote(arguments.getPattern()),
                        caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
        } catch (PatternSyntaxException e) {
            throw new ToolExecutor.ToolExecuteException("搜索表达式语法错误: " + e.getMessage());
        }

        PathMatcher matcher = null;
        if (StringUtils.hasText(filter)) {
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + filter);
            } catch (Exception e) {
                throw new ToolExecutor.ToolExecuteException("glob 过滤表达式错误: " + e.getMessage());
            }
        }

        // 搜索
        List<Match> allMatches = new ArrayList<>();
        try {
            searchFiles(dirPath, dirPath, depth, pattern, matcher, contextLines, maxResults, allMatches);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件搜索失败: " + e.getMessage());
        }

        // 构建输出
        StringBuilder sb = new StringBuilder();
        sb.append("搜索目录: ").append(dirPath).append("\n");
        sb.append("搜索模式: ").append(arguments.getPattern());
        sb.append("（").append(useRegex ? "正则" : "文本").append("，");
        sb.append(caseSensitive ? "区分大小写" : "不区分大小写").append("）\n");
        if (depth > 1) {
            sb.append("递归深度: ").append(depth).append("\n");
        }
        if (StringUtils.hasText(filter)) {
            sb.append("文件过滤: ").append(filter).append("\n");
        }
        sb.append("匹配结果: ").append(allMatches.size()).append(" 条");
        if (allMatches.size() >= maxResults) {
            sb.append("（已达上限，可能有更多结果）");
        }
        sb.append("\n");

        if (allMatches.isEmpty()) {
            sb.append("\n未找到匹配内容。\n");
            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        }

        sb.append("\n");

        // 按文件分组输出
        String currentFile = "";
        for (Match match : allMatches) {
            if (!match.filePath.equals(currentFile)) {
                currentFile = match.filePath;
                sb.append("\n┌─ ").append(currentFile).append("\n");
            }

            // 上下文行（前置）
            if (contextLines > 0 && match.contextBefore != null) {
                for (ContextLine cl : match.contextBefore) {
                    sb.append("│  ").append(String.format("%6d  ", cl.lineNumber)).append(cl.content).append("\n");
                }
            }

            // 匹配行（高亮标记）
            sb.append("│─ ").append(String.format("%6d─ ", match.lineNumber)).append(match.content).append("\n");

            // 上下文行（后置）
            if (contextLines > 0 && match.contextAfter != null) {
                for (ContextLine cl : match.contextAfter) {
                    sb.append("│  ").append(String.format("%6d  ", cl.lineNumber)).append(cl.content).append("\n");
                }
            }
        }

        sb.append("\n");

        return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
    }

    /**
     * 递归搜索目录下的文件
     */
    private void searchFiles(Path rootDir, Path currentDir, int remainingDepth,
                             Pattern pattern, PathMatcher fileMatcher,
                             int contextLines, int maxResults, List<Match> results) throws IOException {
        if (remainingDepth <= 0 || results.size() >= maxResults) {
            return;
        }

        try (Stream<Path> stream = Files.list(currentDir)) {
            List<Path> children = stream.sorted().toList();
            for (Path child : children) {
                if (results.size() >= maxResults) break;
                try {
                    if (Files.isDirectory(child)) {
                        searchFiles(rootDir, child, remainingDepth - 1, pattern, fileMatcher, contextLines, maxResults, results);
                    } else if (Files.isReadable(child)) {
                        // 文件过滤
                        if (fileMatcher != null && !fileMatcher.matches(child.getFileName())) continue;
                        // 跳过二进制/大文件
                        if (isBinary(child)) continue;
                        searchInFile(rootDir, child, pattern, contextLines, maxResults, results);
                    }
                } catch (IOException ignored) {
                    // 跳过无法访问的文件
                }
            }
        }
    }

    /**
     * 在单个文件中搜索匹配行
     */
    private void searchInFile(Path rootDir, Path file, Pattern pattern,
                              int contextLines, int maxResults, List<Match> results) {
        try {
            List<String> allLines;
            try {
                allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                try {
                    allLines = Files.readAllLines(file, Charset.defaultCharset());
                } catch (IOException ignored) {
                    log.warn("文件 {} 读取失败", file);
                    return;
                }
            }

            String relativePath = rootDir.relativize(file).toString();
            int totalLines = allLines.size();

            for (int i = 0; i < totalLines && results.size() < maxResults; i++) {
                String line = allLines.get(i);
                if (pattern.matcher(line).find()) {
                    Match match = new Match();
                    match.filePath = relativePath;
                    match.lineNumber = i + 1;
                    match.content = line;

                    // 收集上下文行
                    if (contextLines > 0) {
                        // 前置上下文
                        List<ContextLine> before = new ArrayList<>();
                        int beforeStart = Math.max(0, i - contextLines);
                        for (int j = beforeStart; j < i; j++) {
                            before.add(new ContextLine(j + 1, allLines.get(j)));
                        }
                        match.contextBefore = before;

                        // 后置上下文
                        List<ContextLine> after = new ArrayList<>();
                        int afterEnd = Math.min(totalLines, i + contextLines + 1);
                        for (int j = i + 1; j < afterEnd; j++) {
                            after.add(new ContextLine(j + 1, allLines.get(j)));
                        }
                        match.contextAfter = after;
                    }

                    results.add(match);
                }
            }
        } catch (IOException e) {
            // 读取失败，静默跳过
        }
    }

    /**
     * 判断文件是否为二进制或过大（不搜索）
     */
    private boolean isBinary(Path file) {
        // 检查扩展名：跳过常见的二进制/媒体文件
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".class") || name.endsWith(".jar")
                || name.endsWith(".war") || name.endsWith(".ear")
                || name.endsWith(".exe") || name.endsWith(".dll")
                || name.endsWith(".so") || name.endsWith(".dylib")
                || name.endsWith(".zip") || name.endsWith(".tar")
                || name.endsWith(".gz") || name.endsWith(".7z")
                || name.endsWith(".rar") || name.endsWith(".bz2")
                || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif")
                || name.endsWith(".bmp") || name.endsWith(".ico")
                || name.endsWith(".svg") || name.endsWith(".webp")
                || name.endsWith(".mp3") || name.endsWith(".mp4")
                || name.endsWith(".avi") || name.endsWith(".mov")
                || name.endsWith(".wav") || name.endsWith(".flac")
                || name.endsWith(".ttf") || name.endsWith(".otf")
                || name.endsWith(".woff") || name.endsWith(".woff2")
                || name.endsWith(".eot") || name.endsWith(".pdf")
                || name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".xls") || name.endsWith(".xlsx")
                || name.endsWith(".ppt") || name.endsWith(".pptx")
                || name.endsWith(".bin") || name.endsWith(".dat")
                || name.endsWith(".db") || name.endsWith(".sqlite")
                || name.endsWith(".o") || name.endsWith(".obj")
                || name.endsWith(".pyc") || name.endsWith(".pyo")
                || name.endsWith(".lock");
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
        private String pattern;
        private Boolean useRegex;
        private String filter;
        private Integer depth;
        private Boolean caseSensitive;
        private Integer contextLines;
        private Integer maxResults;
    }

    @Data
    private static class Match {
        private String filePath;
        private int lineNumber;
        private String content;
        private List<ContextLine> contextBefore;
        private List<ContextLine> contextAfter;
    }

    private record ContextLine(int lineNumber, String content) {}
}
