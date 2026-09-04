package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件写入工具 - 支持 AI 审核和用户确认的文件写入操作
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 06:30
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static com.fishsunny.assistant.engine.tool.framework.ToolKit.inferLanguage;

/**
 * 文件写入工具
 * 要求 dependency 参数传入一个 WebSocketSession 对象
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-write.enable:true}")
public class FileWriteTool implements ToolHandler {

    public static final String AUTO = "auto";
    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";
    public static final String ALWAYS_REJECT_DANGER = "alwaysRejectDanger";

    public static final String NAME = "file_write_tool";
    public static final String SETTINGS = "file_write_tool_settings";

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final SecurityService securityService;

    public FileWriteTool(ObjectMapper objectMapper,
                         @Qualifier(SETTINGS) Settings settings,
                         SecurityService securityService
                         ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.securityService = securityService;
    }

    @Override
    @ToolIncludeContext(key = "session", type = WebSocketSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            WebSocketSession session = (WebSocketSession) context.get("session");

            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getPath())) {
                throw new ToolExecutor.ToolExecuteException("参数 path 不能为空");
            }
            if (arguments.getContent() == null) {
                throw new ToolExecutor.ToolExecuteException("参数 content 不能为空");
            }

            // 路径规范化
            Path filePath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

            // 无审查模式：跳过 AI 危险检测与用户确认，直接执行
            if (!ToolContextUtils.isUnreviewed(context)) {
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(session, arguments, filePath, null);
                        break;
                    case AUTO: {
                        ReviewResult review = isDanger(arguments, filePath, context);
                        if (review.isDanger()) {
                            ask(session, arguments, filePath, review.reason());
                        }
                        break;
                    }
                    case ALWAYS_REJECT_DANGER: {
                        ReviewResult review = isDanger(arguments, filePath, context);
                        if (review.isDanger()) {
                            throw new ToolExecutor.ToolExecuteException(ReviewResult.rejectMessage("此文件写入操作存在危险", review.reason()));
                        }
                        break;
                    }
                    default:
                        throw new ToolExecutor.ToolExecuteException("FileWrite 工具的模式设置错误[" + settings.getMode() + "]，导致该工具无法执行");
                }
            }

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            // 执行文件写入
            File file = filePath.toFile();
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created) {
                    throw new ToolExecutor.ToolExecuteException("无法创建父目录: " + parentDir.getAbsolutePath());
                }
            }

            synchronized (FileToolKit.class) {
                Files.writeString(filePath, arguments.getContent(), StandardCharsets.UTF_8);
            }

            // 统计写入后的文件信息
            long fileSize = Files.size(filePath);
            long lineCount = arguments.getContent().lines().count();
            String language = inferLanguage(filePath);
            String languageInfo = !language.isEmpty() ? language : "未知";

            StringBuilder sb = new StringBuilder();
            sb.append("文件写入成功\n\n");
            sb.append("文件路径: ").append(filePath).append("\n");
            sb.append("文件大小: ").append(ToolKit.formatSize(fileSize)).append("（").append(fileSize).append(" 字节）\n");
            sb.append("写入行数: ").append(lineCount).append("\n");
            sb.append("写入字符: ").append(arguments.getContent().length()).append("\n");
            sb.append("语言类型: ").append(languageInfo).append("\n");
            sb.append("\n写入内容:\n");
            sb.append("````").append(language).append("\n");
            sb.append(arguments.getContent());
            if (!arguments.getContent().endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("````").append("\n");

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件写入失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 使用 AI 对文件写入操作进行危险性评估（子 Agent 审查，可读文件/列目录/解码取证）。
     */
    private ReviewResult isDanger(Arguments arguments, Path filePath, Map<String, Object> context) throws Exception {
        // 写入内容可能包含 % 等字符，禁止用 String.formatted，改用占位符 replace
        String description = """
                文件写入操作
                文件路径：${path}
                文件内容：
                ${content}
                """.replace("${path}", filePath.toString())
                .replace("${content}", arguments.getContent());
        return securityService.review(context, description);
    }

    /**
     * 向用户发送确认请求，等待用户确认
     *
     * @param riskReason AI 审查判定的风险原因（可空；为空时不展示）
     */
    private void ask(WebSocketSession session, Arguments arguments, Path filePath, String riskReason) throws Exception {
        String language = inferLanguage(filePath);
        String content = arguments.getContent();
        int contentLength = content.length();

        String message = "### 文件写入请求\n\n" +
                ReviewResult.riskReasonBlock(riskReason) +
                "**目标文件：** `" + filePath + "`\n\n" +
                "**写入字符数：** " + String.format("%,d", contentLength) + "\n\n" +
                "**内容预览：**\n\n" +
                "```" + language + "\n" +
                content + "\n" +
                "```\n\n" +
                "> 请确认此写入操作安全后再允许执行。";
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
                .setDescription("创建或覆写文件时使用此工具（比执行 echo/重定向命令更安全可靠）。父目录不存在会自动创建，返回写入文件的元信息。" + modeDesc)
                .setRequired(List.of("path", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("path", "string", "文件路径，包含文件名，例如 D:\\projects\\test.txt"),
                        new ToolRegister.Parameters("content", "string", "要写入的文件内容")
                ));
    }

    @Data
    private static class Arguments {
        private String path;
        private String content;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        // neverAsked, alwaysAsked, auto, alwaysRejectDanger
        private String mode;

        public Settings() {
            this.mode = AUTO;
        }

        public Settings(String mode) {
            this.mode = mode;
        }
    }
}
