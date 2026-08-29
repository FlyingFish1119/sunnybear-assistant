package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件编辑工具 - 基于内容匹配的 diff 编辑，支持替换和删除操作，包含 AI 安全审核和用户确认机制
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 08:00
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.file.FilePathLock;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.ToolContextBuilder;
import com.fishsunny.assistant.constants.ControlSign;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文件编辑工具
 * <p>
 * 基于内容匹配进行文件编辑：
 * <ul>
 *   <li>在文件中精确查找 oldContent</li>
 *   <li>用 newContent 替换 oldContent</li>
 *   <li>若 newContent 为空字符串，则删除 oldContent</li>
 *   <li>oldContent 必须在文件中精确匹配且唯一（仅出现一次），否则报错</li>
 * </ul>
 * <p>
 * 要求 dependency 参数传入一个 WebSocketSession 对象
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-edit.enable:true}")
public class FileEditTool implements ToolHandler {

    public static final String AUTO = "auto";
    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";
    public static final String ALWAYS_REJECT_DANGER = "alwaysRejectDanger";

    public static final String NAME = "file_edit_tool";
    public static final String SETTINGS = "file_edit_tool_settings";

    /** 变更区域前后的上下文行数 */
    private static final int CONTEXT_LINES = 2;

    /** 行号格式化宽度 */
    private static final int LINE_NUM_WIDTH = 6;

    /** 用户确认等待超时时间（毫秒） */
    private static final int CONFIRM_TIMEOUT_MS = 30 * 1000;

    /** 轮询用户确认状态的间隔（毫秒） */
    private static final int POLL_INTERVAL_MS = 100;

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings aiSettings;

    public FileEditTool(ObjectMapper objectMapper,
                        @Qualifier(SETTINGS) Settings settings,
                        @Qualifier(AISettings.CUB) AISettings aiSettings,
                        ChatHttpHandler chatHttpHandler) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.chatHttpHandler = chatHttpHandler;
        this.aiSettings = aiSettings;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        String uuid = UUID.randomUUID().toString();
        FilePathLock.LockHandle lock = null;
        try {
            if (!(context.get("session") instanceof WebSocketSession session)) {
                throw new ToolExecutor.ToolExecuteException("工具内部错误导致此工具不可使用，原因: session 依赖缺失");
            }

            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getPath())) {
                throw new ToolExecutor.ToolExecuteException("参数 path 不能为空");
            }
            if (arguments.getOldContent() == null) {
                throw new ToolExecutor.ToolExecuteException("参数 oldContent 不能为 null，请提供需要匹配的原始内容");
            }
            if (arguments.getNewContent() == null) {
                arguments.setNewContent("");
            }

            // 路径规范化
            Path filePath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

            // 加锁：须覆盖 AI 安全检测与用户确认等待，保证 read-modify-write 整体原子化，
            // 防止并发编辑同一文件时互相覆盖（lost update）
            lock = FilePathLock.acquire(filePath);

            // 检查文件
            validateFile(filePath);

            // 读取原始文件内容
            String rawContent = Files.readString(filePath, StandardCharsets.UTF_8);

            // 检测原始换行符并做归一化处理
            String lineSeparator = detectLineSeparator(rawContent);
            String fileContent = normalizeLineEndings(rawContent);
            String oldContent = normalizeLineEndings(arguments.getOldContent());
            String newContent = normalizeLineEndings(arguments.getNewContent());

            // 将文件内容按行拆分（用于 diff 展示和行号映射）
            List<String> allLines = splitLines(fileContent);
            int totalLines = allLines.size();

            // 在文件中查找 oldContent 的唯一匹配
            MatchResult match = findUniqueMatch(fileContent, allLines, oldContent);

            // 判断操作类型
            boolean isDelete = newContent.isEmpty();
            String modeDesc = isDelete ? "删除" : "替换";

            // 生成 diff 风格预览
            String diffPreview = buildDiffResult(allLines, match, newContent, filePath);

            // 无审查模式：跳过 AI 危险检测与用户确认，直接执行
            if (!ToolContextBuilder.isUnreviewed(context)) {
                // 安全检测
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(uuid, session, filePath, modeDesc, match, diffPreview);
                        break;
                    case AUTO:
                        if (isDanger(filePath, modeDesc, diffPreview, match)) {
                            ask(uuid, session, filePath, modeDesc, match, diffPreview);
                        }
                        break;
                    case ALWAYS_REJECT_DANGER:
                        if (isDanger(filePath, modeDesc, diffPreview, match)) {
                            throw new ToolExecutor.ToolExecuteException("AI 判定此文件编辑操作存在危险，操作被拒绝");
                        }
                        break;
                    default:
                        throw new ToolExecutor.ToolExecuteException("FileEdit 工具的模式设置错误[" + settings.getMode() + "]，导致该工具无法执行");
                }
            }

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            // 执行文件编辑：在归一化后的内容中替换
            String resultContent = fileContent.substring(0, match.startOffset)
                    + newContent
                    + fileContent.substring(match.endOffset);

            // 还原原始换行符
            if (!"\n".equals(lineSeparator)) {
                resultContent = resultContent.replace("\n", lineSeparator);
            }
            Files.writeString(filePath, resultContent, StandardCharsets.UTF_8);

            // 构建元数据描述
            String metaBuilder = "文件编辑成功\n\n" +
                    "文件路径: " + filePath + "\n" +
                    "编辑模式: " + modeDesc + "\n" +
                    "匹配行范围: 第 " + (match.startLine + 1) +
                    " ~ " + (match.endLine + 1) + " 行\n" +
                    "文件编辑前总行数: " + totalLines + "\n" +
                    "\n变更预览:\n";

            return new ToolExecutor.ToolExecuteResponse(name(), metaBuilder + diffPreview);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件编辑失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } finally {
            if (lock != null) lock.close();
            ChatController.cleanupConfirm(uuid);
        }
    }

    // ======================== 内容匹配 ========================

    /**
     * 在文件内容中查找 oldContent 的唯一匹配位置
     *
     * @param fileContent 归一化后的文件全文
     * @param allLines    按行拆分后的文件内容
     * @param oldContent  要查找的原始内容
     * @return 匹配结果（包含偏移量和行号）
     * @throws ToolExecutor.ToolExecuteException 未找到或找到多处匹配时抛出
     */
    private MatchResult findUniqueMatch(String fileContent, List<String> allLines, String oldContent)
            throws ToolExecutor.ToolExecuteException {

        // 收集所有匹配位置
        List<Integer> matchPositions = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int pos = fileContent.indexOf(oldContent, searchFrom);
            if (pos < 0) break;
            matchPositions.add(pos);
            searchFrom = pos + 1; // 重叠匹配也计入，避免漏报
        }

        if (matchPositions.isEmpty()) {
            throw new ToolExecutor.ToolExecuteException(
                    "在文件中未找到 oldContent 的匹配内容。" +
                    "请确认内容是否正确（注意空白字符、缩进和换行符的差异）。");
        }

        if (matchPositions.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("oldContent 在文件中匹配到 ").append(matchPositions.size())
              .append(" 处，必须唯一匹配。各匹配位置如下：\n");
            for (int i = 0; i < matchPositions.size(); i++) {
                int pos = matchPositions.get(i);
                int line = offsetToLine(fileContent, pos);
                sb.append("  - 匹配 ").append(i + 1).append(": 第 ").append(line + 1).append(" 行\n");
            }
            sb.append("请增加更多上下文使 oldContent 能够唯一匹配。");
            throw new ToolExecutor.ToolExecuteException(sb.toString());
        }

        int startOffset = matchPositions.get(0);
        int endOffset = startOffset + oldContent.length();

        int startLine = offsetToLine(fileContent, startOffset);
        // endOffset - 1 是因为 endOffset 指向匹配内容之后的首个字符，需要回退一个字符来确定行号
        int endLine = offsetToLine(fileContent, Math.max(0, endOffset - 1));

        return new MatchResult(startOffset, endOffset, startLine, endLine);
    }

    /**
     * 将字符偏移量转换为行号（0-based）
     * <p>
     * 假定内容已归一化为 \n 换行符。
     */
    private int offsetToLine(String content, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ======================== diff 预览构建 ========================

    /**
     * 构建 diff 风格的变更预览
     * <p>
     * 输出顺序：上下文前 → 删除行(-) → 添加行(+) → 上下文后
     */
    private String buildDiffResult(List<String> allLines, MatchResult match,
                                   String newContent, Path filePath) {
        int totalLines = allLines.size();
        int delStart = match.startLine;
        int delEnd = match.endLine;

        List<String> addedLines = newContent.isEmpty()
                ? List.of()
                : splitLines(newContent);

        // 上下文窗口
        int ctxStart = Math.max(0, delStart - CONTEXT_LINES);
        int ctxEnd = Math.min(totalLines - 1, delEnd + CONTEXT_LINES);

        StringBuilder sb = new StringBuilder();

        // 阶段1：上下文前（变更区域之前的未修改行）
        for (int i = ctxStart; i < delStart; i++) {
            sb.append(formatLine(" ", i + 1, allLines.get(i)));
        }

        // 阶段2：删除的行（- 标记）
        for (int i = delStart; i <= delEnd; i++) {
            sb.append(formatLine("-", i + 1, allLines.get(i)));
        }

        // 阶段3：添加的行（+ 标记，行号沿用删除起始行号）
        for (int j = 0; j < addedLines.size(); j++) {
            sb.append(formatLine("+", delStart + 1 + j, addedLines.get(j)));
        }

        // 阶段4：上下文后（变更区域之后的未修改行）
        for (int i = delEnd + 1; i <= ctxEnd && i < totalLines; i++) {
            sb.append(formatLine(" ", i + 1, allLines.get(i)));
        }

        return "````" + ToolKit.inferLanguage(filePath) + "\n" + sb + "````";
    }

    /**
     * 格式化单行输出
     *
     * @param prefix  标记前缀：空格（上下文）、'-'（删除）、'+'（添加）
     * @param lineNum 行号（1-based）
     * @param content 行内容
     */
    private String formatLine(String prefix, int lineNum, String content) {
        return String.format("%s%" + LINE_NUM_WIDTH + "d| ", prefix, lineNum) + content + "\n";
    }

    // ======================== 文件操作辅助方法 ========================

    /**
     * 验证文件路径是否合法
     */
    private void validateFile(Path filePath) throws ToolExecutor.ToolExecuteException {
        if (!Files.exists(filePath)) {
            throw new ToolExecutor.ToolExecuteException("文件不存在: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            throw new ToolExecutor.ToolExecuteException("路径指向的是目录而非文件: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            throw new ToolExecutor.ToolExecuteException("文件不可读: " + filePath);
        }
    }

    /**
     * 检测文件使用的换行符
     */
    private String detectLineSeparator(String content) {
        if (content.contains("\r\n")) {
            return "\r\n";
        } else if (content.contains("\r")) {
            return "\r";
        }
        return "\n";
    }

    /**
     * 将各种换行符归一化为 \n
     */
    private String normalizeLineEndings(String content) {
        if (content == null) return "";
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 将归一化后的内容按行拆分
     */
    private List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        Collections.addAll(lines, content.split("\n", -1));
        return lines;
    }

    // ======================== AI 安全检测 ========================

    /**
     * 使用 AI 对文件编辑操作进行危险性评估
     */
    private boolean isDanger(Path filePath, String modeDesc, String previewContent,
                             MatchResult match) throws Exception {
        String systemPrompt = """
                你是一个文件编辑安全检测器。你的唯一任务是对给出的文件编辑操作进行危险性评估。
                危险操作指编辑后可能导致系统损坏、数据丢失、权限泄露、未授权访问、植入后门或资源耗尽等严重后果的行为。
                请综合考虑以下因素：
                - 文件路径是否指向系统关键目录
                - 文件扩展名是否为可执行/脚本类型
                - 编辑操作（删除、替换）是否会导致恶意行为
                - 编辑后的内容是否包含恶意代码或敏感操作
                请严格按以下格式输出，不添加任何其他内容：
                - 如果操作危险，仅输出：true
                - 如果操作安全，仅输出：false
                记住，你绝对不能输出除了 true 和 false 之外的任何内容。
                """;
        String userPrompt = """
                需要检测的文件编辑操作如下：
                编辑模式：${mode}
                文件路径：${path}
                操作范围：第 ${startLine} ~ ${endLine} 行
                以下是变更预览（- 表示删除的行，+ 表示添加的行，空格前缀为未变更的上下文行）：
                ${content}
                """.replace("${mode}", modeDesc)
                .replace("${path}", filePath.toString())
                .replace("${startLine}", String.valueOf(match.startLine + 1))
                .replace("${endLine}", String.valueOf(match.endLine + 1))
                .replace("${content}", previewContent);

        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
                ));
        AtomicBoolean isDanger = new AtomicBoolean(false);
        AtomicReference<String> exceptionMessage = new AtomicReference<>("");
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream(),
                null,
                ((result, lastRes) -> {
                    String answer = result.content() != null ? result.content().trim().toLowerCase() : "";
                    if ("true".equals(answer)) {
                        isDanger.set(true);
                    } else if ("false".equals(answer)) {
                        isDanger.set(false);
                    } else {
                        exceptionMessage.set("危险解析器输出了无法识别的格式[" + result.content() + "]，工具停止执行。");
                    }
                })
        );
        if (StringUtils.hasText(exceptionMessage.get())) {
            throw new ToolExecutor.ToolExecuteException(exceptionMessage.get());
        }
        return isDanger.get();
    }

    // ======================== 用户确认 ========================

    /**
     * 向用户发送确认请求，等待用户确认
     */
    private void ask(String uuid, WebSocketSession session, Path filePath,
                     String modeDesc, MatchResult match, String previewContent) throws Exception {

        String lineRange = "第 " + (match.startLine + 1) + " ~ " + (match.endLine + 1) + " 行";

        String message = "### 文件编辑请求\n\n"
                + "**目标文件：** `" + filePath + "`\n\n"
                + "**编辑模式：** " + modeDesc + "（" + lineRange + "）\n\n"
                + "**变更预览：**\n\n"
                + previewContent + "\n\n"
                + "> 请确认此编辑操作安全后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message)
                .setTimeout(30);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        Boolean result = ChatController.awaitConfirm(uuid, 30);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认文件编辑操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了文件编辑操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
    }

    // ======================== ToolHandler 接口实现 ========================

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        String modeDesc = switch (settings.getMode()) {
            case ALWAYS_ASKED -> "（每次需确认）";
            case AUTO -> "（危险操作需确认）";
            case ALWAYS_REJECT_DANGER -> "（危险操作直接拒绝）";
            default -> "";
        };
        return new ToolRegister()
                .setName(NAME)
                .setDescription("精确修改文件内容时使用此工具（比执行 sed/awk 命令更安全可靠）。在文件中查找唯一匹配的旧文本并替换为新文本，支持删除操作（newContent 为空时）。" + modeDesc)
                .setRequired(List.of("path", "oldContent"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "文件路径，例如 D:\\projects\\test.txt"),
                        new ToolRegister.Parameters("oldContent", "string",
                                "文件中需要被替换的原始内容（必须与文件中的内容完全一致，包括空白字符和换行）。" +
                                "该内容在文件中必须唯一，若匹配到多处则会报错并列出所有匹配位置，此时需增加更多上下文使其唯一。"),
                        new ToolRegister.Parameters("newContent", "string",
                                "替换后的新内容。若为空字符串则表示删除 oldContent。" +
                                "若要在某处插入新内容，可将该位置的现有行作为 oldContent，并在 newContent 中保留这些行并追加新内容。")
                ));
    }

    // ======================== 内部数据类 ========================

    /**
     * 工具参数
     */
    @Data
    private static class Arguments {
        /** 文件路径 */
        private String path;
        /** 需要被替换的原始内容（必须在文件中唯一匹配） */
        private String oldContent;
        /** 替换后的新内容，为空字符串时表示删除 */
        private String newContent;
    }

    /**
     * 内容匹配结果
     *
     * @param startOffset 匹配内容在归一化文件中的起始字符偏移量
     * @param endOffset   匹配内容在归一化文件中的结束字符偏移量（不包含）
     * @param startLine   匹配起始行号（0-based）
     * @param endLine     匹配结束行号（0-based）
     */
    private record MatchResult(int startOffset, int endOffset, int startLine, int endLine) {
    }

    /**
     * 工具行为设置
     */
    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        // neverAsked, alwaysAsked, auto, alwaysRejectDanger
        private String mode;

        public Settings() {
            this.mode = ALWAYS_ASKED;
        }

        public Settings(String mode) {
            this.mode = mode;
        }
    }
}
