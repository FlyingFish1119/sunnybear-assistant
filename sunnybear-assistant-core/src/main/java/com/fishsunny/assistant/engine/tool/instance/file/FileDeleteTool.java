package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件删除工具 - 支持文件和目录删除，包含 AI 安全审核和用户确认机制
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 12:00
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.DangerChecker;
import com.fishsunny.assistant.engine.tool.service.file.FilePathLock;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.utils.ToolContextBuilder;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文件删除工具
 * 支持删除文件或目录（目录可选择是否递归删除）。
 * 要求 dependency 参数传入一个 WebSocketSession 对象。
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-delete.enable:true}")
public class FileDeleteTool implements ToolHandler {

    public static final String AUTO = "auto";
    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";
    public static final String ALWAYS_REJECT_DANGER = "alwaysRejectDanger";

    public static final String NAME = "file_delete_tool";
    public static final String SETTINGS = "file_delete_tool_settings";

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final DangerChecker dangerChecker;

    public FileDeleteTool(ObjectMapper objectMapper,
                          @Qualifier(SETTINGS) Settings settings,
                          DangerChecker dangerChecker
                          ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.dangerChecker = dangerChecker;
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

            // 路径规范化
            Path targetPath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

            // 加锁：须覆盖 AI 安全检测与用户确认等待，防止删除期间文件被其他会话读写
            lock = FilePathLock.acquire(targetPath);

            if (!Files.exists(targetPath)) {
                throw new ToolExecutor.ToolExecuteException("路径不存在: " + targetPath);
            }

            boolean isDirectory = Files.isDirectory(targetPath);
            boolean recursive = arguments.getRecursive() != null && arguments.getRecursive();

            // 收集删除目标的信息，用于安全检测和展示
            String targetInfo = buildTargetInfo(targetPath, isDirectory, recursive);

            // 无审查模式：跳过 AI 危险检测与用户确认，直接执行
            if (!ToolContextBuilder.isUnreviewed(context)) {
                // 安全检测
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(uuid, session, arguments, targetPath, isDirectory, recursive, targetInfo);
                        break;
                    case AUTO:
                        if (isDanger(arguments, targetPath, isDirectory, recursive, targetInfo)) {
                            ask(uuid, session, arguments, targetPath, isDirectory, recursive, targetInfo);
                        }
                        break;
                    case ALWAYS_REJECT_DANGER:
                        if (isDanger(arguments, targetPath, isDirectory, recursive, targetInfo)) {
                            throw new ToolExecutor.ToolExecuteException("AI 判定此文件删除操作存在危险，操作被拒绝");
                        }
                        break;
                    default:
                        throw new ToolExecutor.ToolExecuteException("FileDelete 工具的模式设置错误[" + settings.getMode() + "]，导致该工具无法执行");
                }
            }

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            // 执行删除
            if (isDirectory) {
                if (recursive) {
                    try (Stream<Path> walk = Files.walk(targetPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.delete(p);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                } else {
                    // 非递归删除目录，要求目录必须为空
                    try (Stream<Path> list = Files.list(targetPath)) {
                        if (list.findFirst().isPresent()) {
                            throw new ToolExecutor.ToolExecuteException("目录不为空，无法删除。如需递归删除请设置 recursive=true: " + targetPath);
                        }
                    }
                    Files.delete(targetPath);
                }
            } else {
                Files.delete(targetPath);
            }

            StringBuilder result = new StringBuilder();
            result.append("文件删除成功\n\n");
            result.append("删除路径: ").append(targetPath).append("\n");
            result.append("目标类型: ").append(isDirectory ? "目录" : "文件").append("\n");
            if (isDirectory && recursive) {
                result.append("递归删除: 是\n");
            }
            result.append("\n删除前信息:\n").append(targetInfo);

            return new ToolExecutor.ToolExecuteResponse(name(), result.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件删除失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } finally {
            if (lock != null) lock.close();
            ChatController.cleanupConfirm(uuid);
        }
    }

    /**
     * 构建删除目标的描述信息
     */
    private String buildTargetInfo(Path targetPath, boolean isDirectory, boolean recursive) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(targetPath, BasicFileAttributes.class);

        StringBuilder sb = new StringBuilder();
        sb.append("  路径: ").append(targetPath).append("\n");
        sb.append("  类型: ").append(isDirectory ? "目录" : "文件").append("\n");
        sb.append("  大小: ").append(ToolKit.formatSize(attrs.size())).append("（").append(attrs.size()).append(" 字节）\n");
        sb.append("  最后修改: ").append(ToolKit.formatTime(attrs.lastModifiedTime())).append("\n");

        if (isDirectory) {
            try (Stream<Path> list = Files.list(targetPath)) {
                long childCount = list.count();
                sb.append("  直接子项数: ").append(childCount).append("\n");
            } catch (IOException ignored) {
                sb.append("  直接子项数: 无法统计\n");
            }
            if (recursive) {
                try (Stream<Path> walk = Files.walk(targetPath)) {
                    long totalCount = walk.count() - 1; // 减去根目录自身
                    sb.append("  递归总项数: ").append(totalCount).append("\n");
                } catch (IOException ignored) {
                    sb.append("  递归总项数: 无法统计\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 使用 AI 对文件删除操作进行危险性评估
     */
    private boolean isDanger(Arguments arguments, Path targetPath, boolean isDirectory,
                             boolean recursive, String targetInfo) throws Exception {
        String systemPrompt = """
                你是一个文件删除安全检测器。你的唯一任务是对给出的文件删除操作进行危险性评估。
                危险操作指删除后可能导致系统损坏、数据丢失、权限泄露、未授权访问或严重后果的行为。
                请综合考虑以下因素：
                - 文件路径是否指向系统关键目录或文件
                - 是否为不可恢复的系统配置、数据库、密钥等
                - 删除目录时是否包含大量子文件
                请严格按以下格式输出，不添加任何其他内容：
                - 如果操作危险，仅输出：true
                - 如果操作安全，仅输出：false
                记住，你绝对不能输出除了 true 和 false 之外的任何内容。
                """;
        String userPrompt = """
                需要检测的文件删除操作如下：
                文件路径：${path}
                是否为目录：${isDir}
                是否递归删除：${recursive}
                目标信息：
                ${targetInfo}
                """.replace("${path}", targetPath.toString())
                .replace("${isDir}", String.valueOf(isDirectory))
                .replace("${recursive}", String.valueOf(recursive))
                .replace("${targetInfo}", targetInfo);

        return dangerChecker.checkDanger(systemPrompt, userPrompt);
    }

    /**
     * 向用户发送确认请求，等待用户确认
     */
    private void ask(String uuid, WebSocketSession session, Arguments arguments, Path targetPath,
                     boolean isDirectory, boolean recursive, String targetInfo) throws Exception {
        String typeLabel = isDirectory ? "目录" : "文件";
        String deleteDesc = isDirectory
                ? (recursive ? "递归删除目录（含所有子项）" : "删除空目录")
                : "删除文件";

        String message = "### 文件删除请求\n\n"
                + "**目标路径：** `" + targetPath + "`\n\n"
                + "**目标类型：** " + typeLabel + "\n\n"
                + "**删除方式：** " + deleteDesc + "\n\n"
                + "**目标信息：**\n\n"
                + "```\n"
                + targetInfo
                + "```\n\n"
                + "> ⚠️ 删除操作不可逆，请确认此操作安全后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message)
                .setTimeout(30);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        Boolean result = ChatController.awaitConfirm(uuid, 30);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认文件删除操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了文件删除操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        String modeDesc = switch (settings.getMode()) {
            case NEVER_ASKED -> "";
            case ALWAYS_ASKED -> "（每次需确认）";
            case AUTO -> "（危险操作需确认）";
            case ALWAYS_REJECT_DANGER -> "（危险操作直接拒绝）";
            default -> "";
        };
        return new ToolRegister()
                .setName(NAME)
                .setDescription("删除文件或目录时使用此工具（比执行 rm/del 命令更安全，有 AI 安全审核）。删除目录需设 recursive=true。" + modeDesc)
                .setRequired(List.of("path"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "要删除的文件或目录路径，例如 D:\\projects\\test.txt"),
                        new ToolRegister.Parameters("recursive", "boolean", "（目录时可选）是否递归删除目录及其所有子项，默认 false，仅删除空目录")
                ));
    }

    @Data
    private static class Arguments {
        private String path;
        private Boolean recursive;
    }

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
