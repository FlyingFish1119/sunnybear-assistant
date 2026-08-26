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
import com.fishsunny.assistant.variable.ControlSign;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.fishsunny.assistant.engine.tool.framwork.ToolKit.inferLanguage;

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
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings aiSettings;

    public FileWriteTool(ObjectMapper objectMapper,
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
            if (arguments.getContent() == null) {
                throw new ToolExecutor.ToolExecuteException("参数 content 不能为空");
            }

            // 路径规范化
            Path filePath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

            // 加锁：须覆盖 AI 安全检测与用户确认等待，防止确认期间文件被其他会话修改
            lock = FilePathLock.acquire(filePath);

            // 无审查模式：跳过 AI 危险检测与用户确认，直接执行
            if (!ToolContextBuilder.isUnreviewed(context)) {
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(uuid, session, arguments, filePath);
                        break;
                    case AUTO:
                        if (isDanger(arguments, filePath)) {
                            ask(uuid, session, arguments, filePath);
                        }
                        break;
                    case ALWAYS_REJECT_DANGER:
                        if (isDanger(arguments, filePath)) {
                            throw new ToolExecutor.ToolExecuteException("AI 判定此文件写入操作存在危险，操作被拒绝");
                        }
                        break;
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

            Files.writeString(filePath, arguments.getContent(), StandardCharsets.UTF_8);

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
        } finally {
            if (lock != null) lock.close();
            ChatController.cleanupConfirm(uuid);
        }
    }

    /**
     * 使用 AI 对文件写入操作进行危险性评估
     */
    private boolean isDanger(Arguments arguments, Path filePath) throws Exception {
        String systemPrompt = """
                你是一个文件写入安全检测器。你的唯一任务是对给出的文件写入操作进行危险性评估。
                危险操作指写入后可能导致系统损坏、数据丢失、权限泄露、未授权访问、植入后门或资源耗尽等严重后果的行为。
                请综合考虑以下因素：
                - 文件路径是否指向系统关键目录
                - 文件扩展名是否为可执行/脚本类型
                - 文件内容是否包含恶意代码或敏感操作
                请严格按以下格式输出，不添加任何其他内容：
                - 如果操作危险，仅输出：true
                - 如果操作安全，仅输出：false
                记住，你绝对不能输出除了 true 和 false 之外的任何内容。
                """;
        String userPrompt = """
                需要检测的文件写入操作如下：
                文件路径：${path}
                文件内容：
                ${content}
                """.replace("${path}", filePath.toString())
                .replace("${content}", arguments.getContent());

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

    /**
     * 向用户发送确认请求，等待用户确认
     */
    private void ask(String uuid, WebSocketSession session, Arguments arguments, Path filePath) throws Exception {
        String language = inferLanguage(filePath);
        String content = arguments.getContent();
        int contentLength = content.length();

        String message = "### 文件写入请求\n\n"
                + "**目标文件：** `" + filePath + "`\n\n"
                + "**写入字符数：** " + String.format("%,d", contentLength) + "\n\n"
                + "**内容预览：**\n\n"
                + "```" + language + "\n"
                + content + "\n"
                + "```\n\n"
                + "> 请确认此写入操作安全后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message)
                .setTimeout(30);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        Boolean result = ChatController.awaitConfirm(uuid, 30);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认文件写入操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了文件写入操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
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
