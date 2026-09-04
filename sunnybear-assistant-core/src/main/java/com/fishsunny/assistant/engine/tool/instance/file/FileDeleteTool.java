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
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.security.ReviewResult;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.utils.ToolContextUtils;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final SecurityService securityService;

    public FileDeleteTool(ObjectMapper objectMapper,
                          @Qualifier(SETTINGS) Settings settings,
                          SecurityService securityService
                          ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.securityService = securityService;
    }

    @Override
    @FileToolKit.FileLock
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
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

            if (!Files.exists(targetPath)) {
                throw new ToolExecutor.ToolExecuteException("路径不存在: " + targetPath);
            }

            boolean isDirectory = Files.isDirectory(targetPath);
            boolean recursive = arguments.getRecursive() != null && arguments.getRecursive();

            // 收集删除目标的信息，用于安全检测和展示
            String targetInfo = buildTargetInfo(targetPath, isDirectory, recursive);

            // 无审查模式：跳过 AI 危险检测与用户确认，直接执行
            if (!ToolContextUtils.isUnreviewed(context)) {
                // 安全检测
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(session, targetPath, isDirectory, recursive, targetInfo, null);
                        break;
                    case AUTO: {
                        ReviewResult review = isDanger(arguments, targetPath, isDirectory, recursive, targetInfo, context);
                        if (review.isDanger()) {
                            ask(session, targetPath, isDirectory, recursive, targetInfo, review.reason());
                        }
                        break;
                    }
                    case ALWAYS_REJECT_DANGER: {
                        ReviewResult review = isDanger(arguments, targetPath, isDirectory, recursive, targetInfo, context);
                        if (review.isDanger()) {
                            throw new ToolExecutor.ToolExecuteException(ReviewResult.rejectMessage("此文件删除操作存在危险", review.reason()));
                        }
                        break;
                    }
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
     * 使用 AI 对文件删除操作进行危险性评估（子 Agent 审查，可读文件/列目录/解码取证）。
     */
    private ReviewResult isDanger(Arguments arguments, Path targetPath, boolean isDirectory,
                                  boolean recursive, String targetInfo, Map<String, Object> context) throws Exception {
        // 目标信息/路径可能包含 % 等字符，禁止用 String.formatted，改用占位符 replace
        String description = """
                文件删除操作
                文件路径：${path}
                是否为目录：${isDir}
                是否递归删除：${recursive}
                目标信息：
                ${info}
                """.replace("${path}", targetPath.toString())
                .replace("${isDir}", String.valueOf(isDirectory))
                .replace("${recursive}", String.valueOf(recursive))
                .replace("${info}", targetInfo);
        return securityService.review(context, description);
    }

    /**
     * 向用户发送确认请求，等待用户确认
     *
     * @param riskReason AI 审查判定的风险原因（可空；为空时不展示）
     */
    private void ask(WebSocketSession session, Path targetPath,
                     boolean isDirectory, boolean recursive, String targetInfo, String riskReason) throws Exception {
        String typeLabel = isDirectory ? "目录" : "文件";
        String deleteDesc = isDirectory
                ? (recursive ? "递归删除目录（含所有子项）" : "删除空目录")
                : "删除文件";

        String message = "### 文件删除请求\n\n"
                + ReviewResult.riskReasonBlock(riskReason)
                + "**目标路径：** `" + targetPath + "`\n\n"
                + "**目标类型：** " + typeLabel + "\n\n"
                + "**删除方式：** " + deleteDesc + "\n\n"
                + "**目标信息：**\n\n"
                + "```\n"
                + targetInfo
                + "```\n\n"
                + "> ⚠️ 删除操作不可逆，请确认此操作安全后再允许执行。";
        securityService.ask(NAME, message, 60, session);
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
