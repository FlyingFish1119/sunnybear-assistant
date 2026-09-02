package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件内容搜索工具 - 基于 ripgrep 后端，在目录中递归搜索文件内容，支持正则/文本匹配、glob 过滤和分页
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
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.file.RipgrepRunner;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 文件内容搜索工具
 * 基于 ripgrep 后端在指定目录下递归搜索文件内容，支持正则表达式匹配、glob 文件过滤、上下文行与结果分页。
 * 自动忽略 .gitignore 规则与常见构建/依赖目录，rg 内部多线程并行，性能远高于手写遍历。
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

    /** 默认排除的构建产物/依赖目录（rg 只读 .gitignore，非 git 目录下的这些目录仍需手动排除，避免超时） */
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            "node_modules", "target", "build", "dist", "out", ".gradle", ".idea", "__pycache__", ".venv", "venv");

    /** ripgrep 后端执行器 */
    private final RipgrepRunner rgRunner;

    public FileSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.rgRunner = new RipgrepRunner(objectMapper);

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("搜索文件内容的首选工具（基于 ripgrep，比执行 findstr/grep 命令更安全、无需用户确认、无输出限制）。支持正则/文本匹配和 glob 文件过滤，自动忽略 .gitignore 与常见构建/依赖目录，根据文字/关键词快速查找相关文件。返回匹配的文件路径、行号和内容，可显示上下文行。" +
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
                        new ToolRegister.Parameters("maxResults", "integer", "最大返回匹配数，默认 " + DEFAULT_MAX_RESULTS + "，上限 " + MAX_RESULTS_LIMIT),
                        new ToolRegister.Parameters("hidden", "boolean", "是否搜索隐藏文件（如 .env、.gitignore 等点文件），默认 false")
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
        int depth = arguments.getDepth() == null ? MAX_DEPTH : Math.clamp(arguments.getDepth(), 1, MAX_DEPTH);
        int maxResults = arguments.getMaxResults() == null ? DEFAULT_MAX_RESULTS : Math.clamp(arguments.getMaxResults(), 1, MAX_RESULTS_LIMIT);
        int contextLines = arguments.getContextLines() == null ? 0 : Math.clamp(arguments.getContextLines(), 0, MAX_CONTEXT_LINES);
        boolean caseSensitive = arguments.getCaseSensitive() != null && arguments.getCaseSensitive();
        boolean useRegex = arguments.getUseRegex() != null && arguments.getUseRegex();

        // 构造 rg 搜索请求
        RipgrepRunner.SearchRequest request = new RipgrepRunner.SearchRequest();
        request.root = dirPath;
        request.pattern = arguments.getPattern();
        request.useRegex = useRegex;
        request.filter = arguments.getFilter();
        request.depth = depth;
        request.caseSensitive = caseSensitive;
        request.contextLines = contextLines;
        request.maxResults = maxResults;
        request.hidden = arguments.getHidden() != null && arguments.getHidden();
        request.excludes = DEFAULT_EXCLUDES;

        RipgrepRunner.SearchResult result;
        try {
            result = rgRunner.search(request);
        } catch (RipgrepRunner.RgUnavailableException e) {
            throw new ToolExecutor.ToolExecuteException("rg 后端不可用: " + e.getMessage() + "（当前平台可能未内置 rg 二进制）");
        } catch (RipgrepRunner.RgExecutionException | IOException e) {
            throw new ToolExecutor.ToolExecuteException("rg 搜索失败: " + e.getMessage());
        } catch (RipgrepRunner.RgTimeoutException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }

        return renderResult(dirPath, arguments, useRegex, caseSensitive, depth, result, maxResults);
    }

    /**
     * 将 rg 结果渲染为文件分组 + 行号 + 匹配/上下文标记的输出
     */
    private ToolExecutor.ToolExecuteResponse renderResult(Path dirPath, Arguments arguments, boolean useRegex,
                                                          boolean caseSensitive, int depth,
                                                          RipgrepRunner.SearchResult result, int maxResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索目录: ").append(dirPath).append("\n");
        sb.append("搜索模式: ").append(arguments.getPattern());
        sb.append("（").append(useRegex ? "正则" : "文本").append("，");
        sb.append(caseSensitive ? "区分大小写" : "不区分大小写").append("）\n");
        if (depth > 1) {
            sb.append("递归深度: ").append(depth).append("\n");
        }
        if (StringUtils.hasText(arguments.getFilter())) {
            sb.append("文件过滤: ").append(arguments.getFilter()).append("\n");
        }
        sb.append("匹配结果: ").append(result.totalMatches).append(" 条");
        if (result.truncated || result.totalMatches >= maxResults) {
            sb.append("（已达上限，可能有更多结果）");
        }
        sb.append("\n");

        boolean any = false;
        for (RipgrepRunner.FileResult file : result.files) {
            boolean hasMatch = file.lines.stream().anyMatch(line -> line.match);
            if (!hasMatch) {
                continue;
            }
            any = true;
            sb.append("\n┌─ ").append(file.relativePath).append("\n");
            for (RipgrepRunner.MatchLine line : file.lines) {
                if (line.match) {
                    sb.append("│─ ").append(String.format("%6d─ ", line.lineNumber)).append(line.content).append("\n");
                } else {
                    sb.append("│  ").append(String.format("%6d  ", line.lineNumber)).append(line.content).append("\n");
                }
            }
        }
        if (!any) {
            sb.append("\n未找到匹配内容。\n");
        }
        sb.append("\n");

        return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
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
        private Boolean hidden;
    }
}
