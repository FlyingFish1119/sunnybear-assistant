package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件读取工具 - 多文件并行读取，每文件独立行范围，含元数据
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 07:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文件读取工具
 * 多文件并行读取，每个文件支持独立的行范围。返回内容含元信息（路径、大小、行数、时间）。
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-read.enable:true}")
public class FileReadTool implements ToolHandler {

    public static final String NAME = "file_read_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** 安全模式下允许直接读取正文的文件大小上限（字节），超过则只返回基础信息 */
    private static final long SAFE_MAX_BYTES = 128 * 1024L;

    /** 统计行数时允许扫描的最大文件大小，超过则不统计（避免为大文件长时间扫描） */
    private static final long LINE_COUNT_MAX_BYTES = 64 * 1024 * 1024L;

    /** 单行默认最多返回的字符数；超过则截断并提示续读位置，可通过 charLimit 调整 */
    private static final int DEFAULT_MAX_LINE_CHARS = 2000;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FileReadTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("读取文件内容的首选工具（比执行 cat/type 命令更安全，无输出限制）。支持多文件并行读取，各文件可独立指定行范围，单文件失败不影响其他文件。" +
                        "默认开启安全模式（safe=true）：读取大文件时只返回文件基础信息而不返回正文，避免撑爆上下文；确需读取正文时显式设置 safe=false。" +
                        "超长单行默认截断到 " + DEFAULT_MAX_LINE_CHARS + " 字符并提示续读位置，可用 startChar/charLimit 分窗口读取。")
                .setRequired(List.of("paths"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("paths", "array",
                                "文件描述数组，每项一个文件，不指定行范围则读全文。")
                                .setItems(ToolRegister.Parameters.object(
                                        "单个文件的读取描述",
                                        List.of(
                                                new ToolRegister.Parameters("path", "string", "文件路径"),
                                                new ToolRegister.Parameters("startLine", "integer", "起始行，从 1 开始，不填则从头读"),
                                                new ToolRegister.Parameters("endLine", "integer", "结束行，包含该行，不填则读到结尾"),
                                                new ToolRegister.Parameters("startChar", "integer", "起始字符位置，从 1 开始，用于读取超长单行的后半段，不填则从行首"),
                                                new ToolRegister.Parameters("charLimit", "integer", "每行最多返回的字符数，默认 " + DEFAULT_MAX_LINE_CHARS + "，用于限制超长单行的输出")),
                                        List.of("path"))),
                        new ToolRegister.Parameters("safe", "boolean",
                                "安全模式，默认 true。开启时读取大文件（超过 " + ToolKit.formatSize(SAFE_MAX_BYTES)
                                        + "）只返回文件基础信息（路径、大小、行数等），不返回正文；"
                                        + "确需读取文件正文时设为 false")
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

        if (arguments.getPaths() == null || arguments.getPaths().isEmpty()) {
            throw new ToolExecutor.ToolExecuteException("参数 paths 不能为空，请至少提供一个文件描述");
        }

        // safe 默认开启：大文件只返回基础信息，避免撑爆上下文
        boolean safe = arguments.getSafe() == null || arguments.getSafe();

        // 并行读取所有文件，每个文件使用自己的行范围
        List<FileResult> results = arguments.getPaths().parallelStream()
                .map(spec -> readFileSafe(spec, safe))
                .toList();

        return assembleResults(results);
    }

    /**
     * 安全读取单个文件描述，捕获异常返回失败结果而不抛出
     */
    private FileResult readFileSafe(FileSpec spec, boolean safe) {
        String pathStr = spec.getPath();
        try {
            if (!StringUtils.hasText(pathStr)) {
                return FileResult.error("(空路径)", "path 不能为空");
            }
            Path filePath = Paths.get(pathStr.trim()).toAbsolutePath().normalize();
            if (!Files.exists(filePath)) {
                return FileResult.error(pathStr, "文件不存在: " + filePath);
            }
            if (Files.isDirectory(filePath)) {
                return FileResult.error(pathStr, "路径指向的是目录而非文件: " + filePath);
            }
            if (!Files.isReadable(filePath)) {
                return FileResult.error(pathStr, "文件不可读: " + filePath);
            }
            String content = readFileContent(filePath, spec, safe);
            return FileResult.success(pathStr, content);
        } catch (Exception e) {
            return FileResult.error(pathStr, "读取异常: " + e.getMessage());
        }
    }

    /**
     * 读取单个文件的文本内容（含元数据头），使用 FileSpec 中的行范围与字符窗口。
     * safe 为 true 时，超过体积上限的文件只返回基础信息。
     * 超长单行按 startChar/charLimit 截取，避免整行灌入上下文。
     */
    private String readFileContent(Path filePath, FileSpec spec, boolean safe) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

        // 安全模式：文件超过体积上限时直接返回元信息，避免大文件正文进入上下文
        if (safe && attrs.size() > SAFE_MAX_BYTES) {
            return buildSafeModeNotice(filePath, attrs, countLines(filePath, attrs.size()));
        }

        int startLine = spec.getStartLine() == null ? 1 : spec.getStartLine();
        int endLine = spec.getEndLine() == null ? Integer.MAX_VALUE : spec.getEndLine();
        int startChar = spec.getStartChar() == null ? 1 : spec.getStartChar();
        int charLimit = spec.getCharLimit() == null ? DEFAULT_MAX_LINE_CHARS : spec.getCharLimit();

        // 验证行号与字符窗口
        if (startLine < 1) {
            throw new ToolExecutor.ToolExecuteException("startLine 不能小于 1，当前值: " + startLine);
        }
        if (endLine < 1) {
            throw new ToolExecutor.ToolExecuteException("endLine 不能小于 1，当前值: " + endLine);
        }
        if (startLine > endLine) {
            throw new ToolExecutor.ToolExecuteException(
                    "startLine(" + startLine + ") 不能大于 endLine(" + endLine + ")");
        }
        if (startChar < 1) {
            throw new ToolExecutor.ToolExecuteException("startChar 不能小于 1，当前值: " + startChar);
        }
        if (charLimit < 1) {
            throw new ToolExecutor.ToolExecuteException("charLimit 不能小于 1，当前值: " + charLimit);
        }

        // 流式扫描：不把整个文件读入内存，只保留选中行、且每行最多 charLimit 个字符
        LineScan scan = scanLines(filePath, startLine, endLine, startChar, charLimit);
        long totalLines = scan.totalLines();

        if (totalLines == 0) {
            return "文件为空，共 0 行。";
        }
        if (startLine > totalLines) {
            throw new ToolExecutor.ToolExecuteException(
                    "startLine(" + startLine + ") 超出文件总行数(" + totalLines + ")");
        }
        int effectiveEndLine = (int) Math.min((long) endLine, totalLines);

        // 根据文件扩展名推断语言标识
        String language = ToolKit.inferLanguage(filePath);

        StringBuilder sb = new StringBuilder();
        appendMetadata(sb, filePath, attrs, totalLines);
        sb.append("\n");
        sb.append("内容（第 ").append(startLine).append(" ~ ").append(effectiveEndLine)
                .append(" 行，共 ").append(totalLines).append(" 行）:\n");
        sb.append("````").append(language).append("\n");

        for (LineView view : scan.lines()) {
            sb.append(String.format("%6d| ", view.lineNo())).append(view.text());
            if (view.truncated()) {
                long shownEnd = (long) startChar + view.text().length() - 1;
                sb.append("  …[本行共 ").append(view.length()).append(" 字符，已显示 ")
                        .append(startChar).append("~").append(shownEnd)
                        .append("，续读请设 startChar=").append(shownEnd + 1).append("]");
            } else if (view.text().isEmpty() && view.length() > 0) {
                sb.append("  …[本行共 ").append(view.length()).append(" 字符，startChar=")
                        .append(startChar).append(" 超出范围]");
            }
            sb.append("\n");
        }

        sb.append("````").append("\n");
        return sb.toString();
    }

    /**
     * 流式扫描文件：统计总行数，并抽取 [startLine, endLine] 范围内每行从 startChar 起的 charLimit 个字符。
     * 逐字符读取，超长单行也只保留窗口片段，不会整体驻留内存。
     */
    private LineScan scanLines(Path filePath, int startLine, int endLine,
                               int startChar, int charLimit) throws IOException {
        List<LineView> views = new ArrayList<>();
        long totalLines = 0;
        long lineNo = 1;
        long lineLength = 0;
        boolean lineHasContent = false;
        boolean pendingCr = false;
        StringBuilder window = new StringBuilder();

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    char c = buf[i];

                    // 上一轮遇到 \r，本轮是 \n：CRLF 视为一个换行，吞掉 \n
                    if (pendingCr) {
                        pendingCr = false;
                        if (c == '\n') {
                            continue;
                        }
                    }

                    if (c == '\n' || c == '\r') {
                        if (lineNo >= startLine && lineNo <= endLine) {
                            views.add(buildLineView((int) lineNo, window, lineLength, startChar));
                        }
                        totalLines++;
                        lineNo++;
                        lineLength = 0;
                        lineHasContent = false;
                        window.setLength(0);
                        if (c == '\r') {
                            pendingCr = true;
                        }
                        continue;
                    }

                    lineHasContent = true;
                    if (lineNo >= startLine && lineNo <= endLine) {
                        lineLength++;
                        if (lineLength >= startChar && window.length() < charLimit) {
                            window.append(c);
                        }
                    }
                }
            }
        }

        // 文件末尾仍有未以换行结尾的一行
        if (lineHasContent) {
            if (lineNo >= startLine && lineNo <= endLine) {
                views.add(buildLineView((int) lineNo, window, lineLength, startChar));
            }
            totalLines++;
        }

        return new LineScan(totalLines, views);
    }

    /**
     * 由窗口片段构建行视图，并判断该行是否被截断
     */
    private LineView buildLineView(int lineNo, StringBuilder window, long lineLength, int startChar) {
        String text = window.toString();
        boolean truncated = lineLength - (startChar - 1L) > text.length();
        return new LineView(lineNo, text, lineLength, truncated);
    }

    /**
     * 拼接文件基础信息（路径、大小、行数、修改时间、语言类型）
     */
    private void appendMetadata(StringBuilder sb, Path filePath, BasicFileAttributes attrs, long totalLines) {
        String language = ToolKit.inferLanguage(filePath);
        sb.append("文件路径: ").append(filePath).append("\n");
        sb.append("文件大小: ").append(ToolKit.formatSize(attrs.size())).append("（").append(attrs.size()).append(" 字节）\n");
        sb.append("总行数: ").append(totalLines >= 0 ? String.valueOf(totalLines) : "未统计（文件过大）").append("\n");
        sb.append("最后修改: ").append(formatTime(attrs.lastModifiedTime())).append("\n");
        sb.append("语言类型: ").append(language.isEmpty() ? "未知" : language).append("\n");
    }

    /**
     * 安全模式拦截大文件时返回的提示（仅基础信息，不含正文）
     */
    private String buildSafeModeNotice(Path filePath, BasicFileAttributes attrs, long totalLines) {
        StringBuilder sb = new StringBuilder();
        appendMetadata(sb, filePath, attrs, totalLines);
        sb.append("\n");
        sb.append("⚠️ 安全模式已开启：文件超过安全读取上限（超过 ")
                .append(ToolKit.formatSize(SAFE_MAX_BYTES))
                .append("），未返回正文内容。\n");
        sb.append("如确需读取文件正文，请显式设置 safe=false 后重试。\n");
        return sb.toString();
    }

    /**
     * 统计文件行数；文件过大或统计失败时返回 -1（未统计）。
     */
    private long countLines(Path filePath, long size) {
        if (size > LINE_COUNT_MAX_BYTES) {
            return -1;
        }
        try (Stream<String> lines = Files.lines(filePath)) {
            return lines.count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 将所有文件的读取结果组装为最终输出
     */
    private ToolExecutor.ToolExecuteResponse assembleResults(List<FileResult> results) {
        long successCount = results.stream().filter(FileResult::success).count();
        long failCount = results.size() - successCount;

        StringBuilder sb = new StringBuilder();

        // 单文件直接输出，无需分隔符和汇总
        if (results.size() == 1) {
            FileResult r = results.getFirst();
            if (r.success()) {
                sb.append(r.content());
            } else {
                sb.append("读取失败: ").append(r.error());
            }
            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        }

        // 多文件：分隔输出 + 汇总
        String separator = "\n" + "=".repeat(60) + "\n";

        for (int i = 0; i < results.size(); i++) {
            FileResult r = results.get(i);
            sb.append(separator);
            sb.append("文件 ").append(i + 1).append("/").append(results.size()).append(": ").append(r.path()).append("\n");
            sb.append(separator);
            if (r.success()) {
                sb.append(r.content());
            } else {
                sb.append("❌ 读取失败: ").append(r.error()).append("\n");
            }
        }

        // 汇总
        sb.append(separator);
        sb.append("汇总: ").append(results.size()).append(" 个文件，成功 ").append(successCount).append(" 个");
        if (failCount > 0) {
            sb.append("，失败 ").append(failCount).append(" 个:\n");
            results.stream().filter(r -> !r.success()).forEach(r ->
                    sb.append("  - ").append(r.path()).append(": ").append(r.error()).append("\n")
            );
        } else {
            sb.append("\n");
        }

        return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
    }

    /**
     * 格式化文件时间为可读字符串
     */
    private String formatTime(FileTime fileTime) {
        if (fileTime == null) {
            return "未知";
        }
        return FORMATTER.format(Instant.ofEpochMilli(fileTime.toMillis()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    /**
     * 单文件读取结果
     */
    private record FileResult(String path, String content, String error, boolean success) {
        static FileResult success(String path, String content) {
            return new FileResult(path, content, null, true);
        }
        static FileResult error(String path, String error) {
            return new FileResult(path, null, error, false);
        }
    }

    /**
     * 单行视图：行号、窗口文本、该行完整字符数、是否被截断
     */
    private record LineView(int lineNo, String text, long length, boolean truncated) {
    }

    /**
     * 文件扫描结果：总行数 + 选中行的视图
     */
    private record LineScan(long totalLines, List<LineView> lines) {
    }

    @Data
    private static class Arguments {
        private List<FileSpec> paths;
        private Boolean safe;
    }

    @Data
    private static class FileSpec {
        private String path;
        private Integer startLine;
        private Integer endLine;
        private Integer startChar;
        private Integer charLimit;
    }
}
