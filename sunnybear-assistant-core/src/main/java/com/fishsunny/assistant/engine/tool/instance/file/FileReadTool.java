package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件读取工具 - 支持元数据提取和内容读取（可选行范围）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 07:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

/**
 * 文件读取工具
 * 支持两种模式：
 * 1. metadata - 提取文件元数据（行数、大小、创建/修改时间等）
 * 2. content  - 读取文件内容，可选指定行范围
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-read.enable:true}")
public class FileReadTool implements ToolHandler {

    public static final String NAME = "file_read_tool";

    public static final String MODE_METADATA = "metadata";
    public static final String MODE_CONTENT = "content";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FileReadTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("读取文件工具，支持两种模式：" +
                        "1) metadata 模式 - 提取文件元数据（行数、大小、创建时间、修改时间等）；" +
                        "2) content 模式 - 读取文件文本内容，可选通过 startLine 和 endLine 指定读取的行范围（行号从1开始，两端均包含）。")
                .setRequired(List.of("path", "mode"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "文件路径，例如 D:\\projects\\test.txt 或 /home/user/file.log"),
                        new ToolRegister.Parameters("mode", "string", "读取模式：metadata（提取元数据）或 content（读取内容）"),
                        new ToolRegister.Parameters("startLine", "integer", "（content 模式可选）起始行号，从1开始，不指定则从第1行开始"),
                        new ToolRegister.Parameters("endLine", "integer", "（content 模式可选）结束行号（包含），不指定则读到文件末尾")
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
        if (!StringUtils.hasText(arguments.getMode())) {
            throw new ToolExecutor.ToolExecuteException("参数 mode 不能为空，请指定为 metadata 或 content");
        }

        Path filePath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

        if (!Files.exists(filePath)) {
            throw new ToolExecutor.ToolExecuteException("文件不存在: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            throw new ToolExecutor.ToolExecuteException("路径指向的是目录而非文件: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            throw new ToolExecutor.ToolExecuteException("文件不可读: " + filePath);
        }

        String mode = arguments.getMode().trim().toLowerCase();

        try {
            return switch (mode) {
                case MODE_METADATA -> handleMetadata(filePath);
                case MODE_CONTENT -> handleContent(filePath, arguments);
                default -> throw new ToolExecutor.ToolExecuteException(
                        "不支持的读取模式: " + mode + "，仅支持 metadata 和 content");
            };
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件读取失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("文件读取异常: " + e.getMessage());
        }
    }

    /**
     * 处理元数据模式：返回文件的行数、大小、时间等信息
     */
    private ToolExecutor.ToolExecuteResponse handleMetadata(Path filePath) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

        long lineCount;
        try (Stream<String> lines = Files.lines(filePath)) {
            lineCount = lines.count();
        } catch (IOException e) {
            lineCount = -1; // 二进制文件可能无法按行读取
        }

        String sb = "文件元数据:\n\n" +
                "  路径: " + filePath + "\n\n" +
                "  文件名: " + filePath.getFileName() + "\n\n" +
                "  大小: " + ToolKit.formatSize(attrs.size()) + " (" + attrs.size() + " 字节)\n\n" +
                "  行数: " + (lineCount >= 0 ? String.valueOf(lineCount) : "无法统计（可能是二进制文件）") + "\n\n" +
                "  创建时间: " + formatTime(attrs.creationTime()) + "\n\n" +
                "  最后修改时间: " + formatTime(attrs.lastModifiedTime()) + "\n\n" +
                "  最后访问时间: " + formatTime(attrs.lastAccessTime()) + "\n\n" +
                "  是否为常规文件: " + (attrs.isRegularFile() ? "是" : "否") + "\n\n" +
                "  是否为符号链接: " + (attrs.isSymbolicLink() ? "是" : "否");

        return new ToolExecutor.ToolExecuteResponse(name(), sb);
    }

    /**
     * 处理内容模式：读取文件的文本内容，支持指定行范围
     */
    private ToolExecutor.ToolExecuteResponse handleContent(Path filePath, Arguments arguments) throws Exception {
        List<String> allLines = Files.readAllLines(filePath);
        int totalLines = allLines.size();

        if (totalLines == 0) {
            return new ToolExecutor.ToolExecuteResponse(name(), "文件为空，共 0 行。");
        }

        // 解析行范围，行号从 1 开始
        int startLine = arguments.getStartLine() == null ? 1 : arguments.getStartLine();
        int endLine = arguments.getEndLine() == null ? totalLines : arguments.getEndLine();

        // 验证行号
        if (startLine < 1) {
            throw new ToolExecutor.ToolExecuteException("startLine 不能小于 1，当前值: " + startLine);
        }
        if (endLine < 1) {
            throw new ToolExecutor.ToolExecuteException("endLine 不能小于 1，当前值: " + endLine);
        }
        if (startLine > totalLines) {
            throw new ToolExecutor.ToolExecuteException(
                    "startLine(" + startLine + ") 超出文件总行数(" + totalLines + ")");
        }
        if (endLine > totalLines) {
            endLine = totalLines; // 自动截断到文件末尾
        }
        if (startLine > endLine) {
            throw new ToolExecutor.ToolExecuteException(
                    "startLine(" + startLine + ") 不能大于 endLine(" + endLine + ")");
        }

        // 提取指定范围的行（转为 0-based 索引）
        List<String> selectedLines = allLines.subList(startLine - 1, endLine);

        // 根据文件扩展名推断语言标识，用于代码高亮
        String language = ToolKit.inferLanguage(filePath);

        // 读取文件元数据
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        String languageInfo = !language.isEmpty() ? language : "未知";

        StringBuilder sb = new StringBuilder();
        sb.append("文件路径: ").append(filePath).append("\n");
        sb.append("文件大小: ").append(ToolKit.formatSize(attrs.size())).append("（").append(attrs.size()).append(" 字节）\n");
        sb.append("总行数: ").append(totalLines).append("\n");
        sb.append("最后修改: ").append(formatTime(attrs.lastModifiedTime())).append("\n");
        sb.append("语言类型: ").append(languageInfo).append("\n");
        sb.append("\n");
        sb.append("内容（第 ").append(startLine).append(" ~ ").append(endLine)
                .append(" 行，共 ").append(totalLines).append(" 行）:\n");
        sb.append("````").append(language).append("\n");

        for (int i = 0; i < selectedLines.size(); i++) {
            int lineNumber = startLine + i;
            sb.append(String.format("%6d| ", lineNumber)).append(selectedLines.get(i)).append("\n");
        }

        sb.append("````").append("\n");

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

    @Data
    private static class Arguments {
        private String path;
        private String mode;
        private Integer startLine;
        private Integer endLine;
    }
}
