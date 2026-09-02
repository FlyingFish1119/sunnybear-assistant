package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 文件下载工具 - 支持从指定 URL 下载文件到本地路径，带有用户确认机制
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 01:10
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.file.FilePathLock;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.utils.ToolContextBuilder;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件下载工具
 * 从指定 URL 下载文件并保存到本地路径，要求 dependency 参数传入一个 WebSocketSession 对象。
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.file-download.enable:true}")
public class FileDownloadTool implements ToolHandler {

    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";

    public static final String NAME = "file_download_tool";
    public static final String SETTINGS = "file_download_tool_settings";

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final Settings settings;

    public FileDownloadTool(ObjectMapper objectMapper, Settings settings) {
        this.objectMapper = objectMapper;
        this.settings = settings;
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
            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数 url 不能为空");
            }
            if (!StringUtils.hasText(arguments.getPath())) {
                throw new ToolExecutor.ToolExecuteException("参数 path 不能为空");
            }

            // 路径规范化
            Path savePath = Paths.get(arguments.getPath()).toAbsolutePath().normalize();

            // 加锁：须覆盖用户确认等待，防止下载写入期间保存路径被其他会话修改
            lock = FilePathLock.acquire(savePath);

            // 无审查模式：跳过用户确认，直接执行
            if (!ToolContextBuilder.isUnreviewed(context)) {
                // 安全检测
                switch (settings.getMode()) {
                    case NEVER_ASKED:
                        break;
                    case ALWAYS_ASKED:
                        ask(uuid, session, arguments, savePath);
                        break;
                    default:
                        throw new ToolExecutor.ToolExecuteException("FileDownload 工具的模式设置错误[" + settings.getMode() + "]，导致该工具无法执行");
                }
            }

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            // 创建父目录
            java.io.File parentDir = savePath.toFile().getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created) {
                    throw new ToolExecutor.ToolExecuteException("无法创建父目录: " + parentDir.getAbsolutePath());
                }
            }

            String result = download(arguments, savePath);
            return new ToolExecutor.ToolExecuteResponse(name(), result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("文件下载失败（IO 错误）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("文件下载被中断: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } finally {
            if (lock != null) lock.close();
            ChatController.cleanupConfirm(uuid);
        }
    }

    private String download(Arguments arguments, Path savePath) throws Exception {
        // 执行下载
        int timeoutSeconds = arguments.getTimeout() != null && arguments.getTimeout() > 0
                ? arguments.getTimeout()
                : DEFAULT_TIMEOUT_SECONDS;

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(arguments.getUrl()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new ToolExecutor.ToolExecuteException("下载失败，HTTP 状态码: " + statusCode);
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            try (InputStream in = response.body()) {
                Files.copy(in, savePath, StandardCopyOption.REPLACE_EXISTING);

                long savedSize = Files.size(savePath);

                StringBuilder sb = new StringBuilder();
                sb.append("文件下载成功\n\n");
                sb.append("下载地址: ").append(arguments.getUrl()).append("\n");
                sb.append("保存路径: ").append(savePath).append("\n");
                if (contentLength > 0) {
                    sb.append("响应大小: ").append(ToolKit.formatSize(contentLength)).append("（").append(contentLength).append(" 字节）\n");
                }
                sb.append("实际大小: ").append(ToolKit.formatSize(savedSize)).append("（").append(savedSize).append(" 字节）\n");
                sb.append("HTTP 状态: ").append(statusCode).append("\n");

                return sb.toString();
            }
        }
    }

    /**
     * 向用户发送确认请求，等待用户确认
     */
    private void ask(String uuid, WebSocketSession session, Arguments arguments, Path savePath) throws Exception {
        int timeoutSeconds = arguments.getTimeout() != null && arguments.getTimeout() > 0
                ? arguments.getTimeout()
                : DEFAULT_TIMEOUT_SECONDS;

        String message = "### 文件下载请求\n\n"
                + "**下载地址：** `" + arguments.getUrl() + "`\n\n"
                + "**保存路径：** `" + savePath + "`\n\n"
                + "**超时时间：** " + timeoutSeconds + " 秒\n\n"
                + "> ⚠️ 请确认下载来源可信后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message)
                .setTimeout(30);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        Boolean result = ChatController.awaitConfirm(uuid, 30);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认文件下载操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了文件下载操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整操作。");
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
            default -> "";
        };
        return new ToolRegister()
                .setName(NAME)
                .setDescription("从 URL 下载文件到本地时使用此工具（比执行 curl/wget 命令更可靠，支持大文件和重定向）。" + modeDesc)
                .setRequired(List.of("url", "path"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("url", "string", "要下载的文件 URL 地址"),
                        new ToolRegister.Parameters("path", "string", "本地保存路径，包含文件名，例如 D:\\projects\\test.zip"),
                        new ToolRegister.Parameters("timeout", "integer", "（可选）下载超时时间（秒），默认 30 秒")
                ));
    }

    @Data
    private static class Arguments {
        private String url;
        private String path;
        private Integer timeout;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        // neverAsked, alwaysAsked
        private String mode;

        public Settings() {
            this.mode = ALWAYS_ASKED;
        }

        public Settings(String mode) {
            this.mode = mode;
        }
    }
}
