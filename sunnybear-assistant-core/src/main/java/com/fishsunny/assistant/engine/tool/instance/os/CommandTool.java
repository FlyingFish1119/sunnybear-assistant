package com.fishsunny.assistant.engine.tool.instance.os;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 01:02
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.engine.tool.service.security.ReviewResult;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 命令行工具
 * 要求 dependency 参数传入一个 WebSocketSession 对象
 */
@ToolKitComponent(OSToolKit.class)
@ConditionalOnExpression("${engine.tool.os.enable:true} && ${engine.tool.os.command.enable:true}")
public class CommandTool implements ToolHandler {

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();

    public static final String AUTO = "auto";
    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";
    public static final String ALWAYS_REJECT_DANGER = "alwaysRejectDanger";

    public static final String NAME = "command_tool";
    public static final String SETTINGS = "command_tool_settings";

    /** auto 模式下的安全命令白名单（Windows），命中则跳过 AI 判断直接执行 */
    private static final List<String> DEFAULT_WHITE_LIST_WINDOWS = List.of(
            "dir", "echo", "cd", "type", "set", "help", "ver", "date", "time",
            "whoami", "hostname", "ipconfig", "nslookup", "netstat", "tasklist",
            "where", "findstr", "tree", "cls", "path", "assoc", "ftype"
    );

    /** auto 模式下的危险命令黑名单（Windows），命中则强制询问用户 */
    private static final List<String> DEFAULT_BLACK_LIST_WINDOWS = List.of(
            "rmdir /s", "del /f", "del /s", "format", "diskpart",
            "reg delete", "reg add", "bcdedit", "shutdown", "netsh",
            "icacls", "takeown", "cacls", "sc delete", "wmic delete"
    );

    /** auto 模式下的安全命令白名单（Linux），命中则跳过 AI 判断直接执行 */
    private static final List<String> DEFAULT_WHITE_LIST_LINUX = List.of(
            "ls", "echo", "cd", "cat", "pwd", "whoami", "hostname", "date",
            "uname", "uptime", "df", "du", "free", "ps", "top", "htop",
            "ifconfig", "ip", "netstat", "ss", "ping", "nslookup", "dig",
            "curl", "wget", "head", "tail", "less", "more", "grep", "find",
            "which", "whereis", "file", "stat", "wc", "sort", "uniq", "cut",
            "tr", "env", "printenv", "id", "groups", "tree", "clear",
            "awk", "sed", "xargs", "tee"
    );

    /** auto 模式下的危险命令黑名单（Linux），命中则强制询问用户 */
    private static final List<String> DEFAULT_BLACK_LIST_LINUX = List.of(
            "rm -rf", "rm -r", "mkfs", "dd", "fdisk", "parted",
            "shutdown", "reboot", "halt", "poweroff", "init 0", "init 6",
            "chmod 777", "chown", "usermod", "userdel", "groupdel",
            "iptables", "ufw disable", "systemctl disable", "systemctl stop",
            "kill -9", "pkill", "killall", ":(){ :|:& };:", "chroot",
            "mount", "umount", "mkswap", "swapon"
    );

    /** 获取当前平台的默认白名单 */
    private static List<String> getDefaultWhiteList() {
        return IS_WINDOWS ? DEFAULT_WHITE_LIST_WINDOWS : DEFAULT_WHITE_LIST_LINUX;
    }

    /** 获取当前平台的默认黑名单 */
    private static List<String> getDefaultBlackList() {
        return IS_WINDOWS ? DEFAULT_BLACK_LIST_WINDOWS : DEFAULT_BLACK_LIST_LINUX;
    }

    /** 获取当前平台的 Shell 命令行前缀 */
    private static String[] getShell() {
        return IS_WINDOWS ? WINDOWS_SHELL : LINUX_SHELL;
    }

    /** 获取当前平台 Shell 名称（用于提示信息） */
    private static String getShellName() {
        return IS_WINDOWS ? "cmd.exe" : "bash";
    }

    /** 获取当前平台操作系统名称 */
    private static String getOsName() {
        return IS_WINDOWS ? "Windows" : "Linux";
    }

    /** 用户确认等待超时时间（毫秒） */
    private static final int CONFIRM_TIMEOUT_MS = 30 * 1000;

    /** 轮询用户确认状态的间隔（毫秒） */
    private static final int POLL_INTERVAL_MS = 100;

    /** 默认命令执行超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** 默认安全输出大小限制（字节） */
    private static final long DEFAULT_SAFETY_OUTPUT_SIZE = 8192L;

    /** 默认最大输出大小限制（字节） */
    private static final long DEFAULT_MAX_OUTPUT_SIZE = 32768L;

    /** 当前操作系统是否为 Windows */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** Windows 命令行前缀 */
    private static final String[] WINDOWS_SHELL = {"cmd.exe", "/c"};

    /** Linux/Mac 命令行前缀 */
    private static final String[] LINUX_SHELL = {"bash", "-c"};

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final SecurityService securityService;

    @Value("${assistant.file.base-path:}")
    private String basePath;

    public CommandTool(ObjectMapper objectMapper,
                       @Qualifier(SETTINGS) Settings settings,
                       SecurityService securityService
                       ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.securityService = securityService;
    }

    @Override
    @ToolIncludeContext(key = {"session", "chatSession"}, type = {WebSocketSession.class, ChatSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            WebSocketSession session = (WebSocketSession) context.get("session");

            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getCommand())) {
                throw new ToolExecutor.ToolExecuteException("参数 command 不能为空");
            }

            switch (settings.getMode()) {
                case AUTO: {
                    if (isBlacklisted(arguments.getCommand())) {
                        ask(context, arguments, null);
                    } else if (!isWhitelisted(arguments.getCommand())) {
                        ReviewResult review = isDanger(arguments, context);
                        if (review.isDanger()) {
                            ask(context, arguments, review.reason());
                        }
                    }
                    break;
                }
                case ALWAYS_ASKED:
                    ask(context, arguments, null);
                    break;
                case NEVER_ASKED:
                    break;
                case ALWAYS_REJECT_DANGER: {
                    if (isBlacklisted(arguments.getCommand())) {
                        throw new ToolExecutor.ToolExecuteException("此命令行命令被拒绝执行");
                    } else if (!isWhitelisted(arguments.getCommand())) {
                        ReviewResult review = isDanger(arguments, context);
                        if (review.isDanger()) {
                            throw new ToolExecutor.ToolExecuteException(ReviewResult.rejectMessage("执行此命令行命令存在危险", review.reason()));
                        }
                    }
                    break;
                }
                default:
                    throw new ToolExecutor.ToolExecuteException("Command 工具的模式设置错误[" + settings.getMode() +"]，导致该工具无法执行");
            }

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            // ======================== 后台执行模式 ========================
            if (Boolean.TRUE.equals(arguments.getBackground())) {
                return executeInBackground(arguments.getCommand(), context);
            }

            // ======================== 前台执行模式 ========================
            String[] shell = getShell();
            ProcessBuilder processBuilder = new ProcessBuilder(shell[0], shell[1], arguments.getCommand());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            Future<String> future = EXECUTOR_SERVICE.submit(() -> {
                byte[] bytes = process.getInputStream().readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            });

            String result;
            try {
                result = future.get(settings.getTimeout(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                throw new ToolExecutor.ToolExecuteException("命令执行超时（" + settings.getTimeout() + "秒），如果该命令打开了一个进程用于运行GUI等，那么这个报错是正常。");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ToolExecutor.ToolExecuteException("命令执行失败，退出码：" + exitCode + "，输出：" + result);
            }

            long outputSize = result.getBytes(StandardCharsets.UTF_8).length;

            // 1. 硬限制检查：maxOutputSize 始终生效，不受 skipSafeMode 影响
            Long maxSize = settings.getMaxOutputSize();
            if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
                throw new ToolExecutor.ToolExecuteException(
                        "命令输出大小（" + ToolKit.formatSize(outputSize) + "）超过最大允许限制（" + ToolKit.formatSize(maxSize) + "），已拒绝执行。" +
                        "请尝试缩小命令的输出范围，例如使用更精确的筛选条件（如 findstr 过滤）或限制输出行数。"
                );
            }

            // 2. 安全限制检查：仅在未跳过安全模式时生效
            if (!Boolean.TRUE.equals(arguments.getSkipSafeMode())) {
                Long safetySize = settings.getSafetyOutputSize();
                if (safetySize != null && safetySize > 0 && outputSize > safetySize) {
                    throw new ToolExecutor.ToolExecuteException(
                            "命令输出大小（" + ToolKit.formatSize(outputSize) + "）超过安全限制（" + ToolKit.formatSize(safetySize) + "），已拦截返回。" +
                            "如需获取完整输出，请设置参数 skipSafeMode=true 跳过安全限制；" +
                            "或缩小命令的输出范围（如使用 findstr 过滤、只读取部分内容等）。"
                    );
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 按 shell 的元字符（&amp;&amp;, ||, &amp;, |, ;）拆分子命令。
     * 仅当元字符位于双引号之外时才作为分隔符，避免误拆引号内的内容。
     * Linux 下额外支持 ; 作为命令分隔符。
     */
    private List<String> splitCommands(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (!inQuotes) {
                if (c == '&') {
                    if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                        // &&
                        String sub = current.toString().trim();
                        if (!sub.isEmpty()) result.add(sub);
                        current = new StringBuilder();
                        i++;
                    } else {
                        // &
                        String sub = current.toString().trim();
                        if (!sub.isEmpty()) result.add(sub);
                        current = new StringBuilder();
                    }
                } else if (c == '|') {
                    if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                        // ||
                        String sub = current.toString().trim();
                        if (!sub.isEmpty()) result.add(sub);
                        current = new StringBuilder();
                        i++;
                    } else {
                        // |
                        String sub = current.toString().trim();
                        if (!sub.isEmpty()) result.add(sub);
                        current = new StringBuilder();
                    }
                } else if (!IS_WINDOWS && c == ';') {
                    // ; 仅在 Linux/Mac 下作为命令分隔符
                    String sub = current.toString().trim();
                    if (!sub.isEmpty()) result.add(sub);
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            } else {
                current.append(c);
            }
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            result.add(last);
        }

        return result;
    }

    /**
     * 检查子命令是否匹配指定的模式列表。
     * 安全匹配规则：子命令等于模式，或以 "模式 "（模式后跟空格）开头。
     * 避免 startsWith 的误匹配（如 "dir" 误匹配 "dirname"）。
     */
    private boolean matchesPattern(String subCommand, List<String> patterns) {
        String lower = subCommand.toLowerCase().trim();
        for (String pattern : patterns) {
            String pLower = pattern.toLowerCase();
            if (lower.equals(pLower) || lower.startsWith(pLower + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查命令是否命中黑名单。
     * 将命令拆分为子命令，任一子命令命中黑名单即视为命中。
     */
    private boolean isBlacklisted(String command) {
        List<String> blackList = settings.getBlackList();
        if (blackList == null || blackList.isEmpty()) {
            blackList = getDefaultBlackList();
        }
        List<String> subCommands = splitCommands(command);
        for (String sub : subCommands) {
            if (matchesPattern(sub, blackList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查命令是否命中白名单。
     * 将命令拆分为子命令，所有子命令都必须命中白名单才视为命中。
     * 如果命令包含元字符（多个子命令），则每一个子命令都必须可安全执行。
     */
    private boolean isWhitelisted(String command) {
        List<String> whiteList = settings.getWhiteList();
        if (whiteList == null || whiteList.isEmpty()) {
            whiteList = getDefaultWhiteList();
        }
        List<String> subCommands = splitCommands(command);
        if (subCommands.isEmpty()) {
            return false;
        }
        for (String sub : subCommands) {
            if (!matchesPattern(sub, whiteList)) {
                return false;
            }
        }
        return true;
    }

    private ReviewResult isDanger(Arguments arguments, Map<String, Object> context) throws Exception {
        // 命令文本可能包含 % 等字符，禁止用 String.formatted，改用占位符 replace
        String description = """
                在终端执行命令
                命令内容：
                ${command}
                """.replace("${command}", arguments.getCommand());
        return securityService.review(context, description);
    }

    /**
     * @param riskReason AI 审查判定的风险原因（可空；为空时不展示）
     */
    private void ask(Map<String, Object> context, Arguments arguments, String riskReason) throws Exception {
        String shellName = IS_WINDOWS ? "cmd" : "bash";
        String message = "### 命令执行请求\n\n"
                + ReviewResult.riskReasonBlock(riskReason)
                + "AI 请求在系统中执行以下命令：\n\n"
                + "```" + shellName + "\n"
                + arguments.getCommand() + "\n"
                + "```\n\n"
                + "> 请确认此命令安全后再允许执行。";
        securityService.ask(NAME, message, null, context);
    }

    /**
     * 后台执行命令：将命令放入守护线程执行，输出以追加模式写入 session/file 目录。
     * <p>
     * 后台模式特点：
     * <ul>
     *   <li>无超时限制 —— 命令可以运行任意长时间</li>
     *   <li>无输出大小限制 —— 输出直接写入文件，不经过内存缓存</li>
     *   <li>输出实时写入 —— 使用流式读取，每读取到数据立即 flush 到文件</li>
     *   <li>返回文件路径 —— AI 可通过 session_file_tool 或 file_read_tool 查看输出</li>
     * </ul>
     * </p>
     *
     * @param command 要执行的命令
     * @param context 工具上下文（用于获取 sessionId）
     * @return 包含日志文件路径的响应
     */
    private ToolExecutor.ToolExecuteResponse executeInBackground(String command, Map<String, Object> context) throws Exception {
        // action 已声明 chatSession 依赖（@ToolIncludeContext），此处直接取用
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession.getId();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "command_" + timestamp + ".log";
        Path logDir = Paths.get(basePath, "session", sessionId, "file");
        Path logFile = logDir.resolve(fileName);

        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建后台输出目录 [" + logDir + "]: " + e.getMessage());
        }

        String shellName = getShellName();
        String osName = getOsName();

        // 先写入文件头
        try (BufferedWriter headerWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            headerWriter.write("=== 后台命令执行日志 ===");
            headerWriter.newLine();
            headerWriter.write("启动时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            headerWriter.newLine();
            headerWriter.write("Shell: " + shellName + " (" + osName + ")");
            headerWriter.newLine();
            headerWriter.write("命令: " + command);
            headerWriter.newLine();
            headerWriter.write("日志文件: " + logFile.toAbsolutePath());
            headerWriter.newLine();
            headerWriter.write("=".repeat(60));
            headerWriter.newLine();
            headerWriter.newLine();
            headerWriter.flush();
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法写入后台日志文件 [" + logFile + "]: " + e.getMessage());
        }


        // 在守护线程中执行命令，流式写入输出（直接写字节，避免编码边界问题）
        Thread backgroundThread = new Thread(() -> {
            try {
                String[] shell = getShell();
                ProcessBuilder processBuilder = new ProcessBuilder(shell[0], shell[1], command);
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();

                OSToolKit.writeLog(logFile, process);
                int exitCode = process.waitFor();

                // 写入结束标记
                try (BufferedWriter footerWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    footerWriter.newLine();
                    footerWriter.write("=".repeat(60));
                    footerWriter.newLine();
                    footerWriter.write("命令执行完成，退出码: " + exitCode);
                    footerWriter.newLine();
                    footerWriter.write("结束时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    footerWriter.newLine();
                    footerWriter.flush();
                }
            } catch (Exception e) {
                try (BufferedWriter errorWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    errorWriter.newLine();
                    errorWriter.write("=".repeat(60));
                    errorWriter.newLine();
                    errorWriter.write("命令执行异常: " + e.getMessage());
                    errorWriter.newLine();
                    errorWriter.write("结束时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    errorWriter.newLine();
                    errorWriter.flush();
                } catch (IOException ignored) {
                    // 无法写入错误信息，静默忽略
                }
            }
        }, "command-bg-" + timestamp);

        backgroundThread.setDaemon(true);
        backgroundThread.start();

        String message = "命令已在后台启动执行（）。\n"
                + "输出日志文件: " + logFile.toAbsolutePath() + "\n"
                + "> 提示：使用 file_read_tool 读取日志文件内容查看命令输出。"
                + "命令执行完成后，日志末尾会写入退出码和结束时间。";

        return new ToolExecutor.ToolExecuteResponse(name(), message);
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
                .setDescription("在终端中执行命令。" + modeDesc + "默认有安全输出限制。")
                .setRequired(List.of("command"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("command", "string", "你准备执行的命令"),
                        new ToolRegister.Parameters("skipSafeMode", "boolean", "设为 true 跳过安全限制以获取完整输出（大输出时建议开启）"),
                        new ToolRegister.Parameters("background", "boolean", "设为 true 后台执行（无超时限制），适合长时间任务。输出写入日志文件。")
                ));
    }

    /**
     * 构建输出限制的描述信息
     */
    private String buildLimitDescription() {
        StringBuilder sb = new StringBuilder();
        Long safetySize = settings.getSafetyOutputSize();
        Long maxSize = settings.getMaxOutputSize();
        if (safetySize != null && safetySize > 0) {
            sb.append(" 命令输出安全限制为 ").append(ToolKit.formatSize(safetySize)).append("（超过此限制将被拦截）。");
        }
        if (maxSize != null && maxSize > 0) {
            sb.append(" 最大输出限制为 ").append(ToolKit.formatSize(maxSize)).append("（硬限制，不可跳过）。");
        }
        return sb.toString();
    }

    @Data
    private static class Arguments {
        private String command;
        private Boolean skipSafeMode;
        /** 设为 true 时命令在后台执行：无超时限制、无输出大小限制，输出以追加模式写入 session/file 目录下的日志文件 */
        private Boolean background;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        // auto, alwaysAsked, neverAsked, alwaysRejectDanger
        private String mode;
        /** auto 模式下的白名单，命中则跳过危险检测直接执行 */
        private List<String> whiteList;
        /** auto 模式下的黑名单，命中则强制询问用户 */
        private List<String> blackList;
        /** 命令执行超时时间，单位秒 */
        private Long timeout;
        /** 超过输出字节数则默认拒绝（UTF-8 编码） */
        private Long safetyOutputSize;
        /** 输出字节数超过此值则直接拒绝执行 */
        private Long maxOutputSize;
        public Settings() {
            this.mode = "auto";
            this.timeout = (long) DEFAULT_TIMEOUT_SECONDS;
            this.safetyOutputSize = DEFAULT_SAFETY_OUTPUT_SIZE;
            this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        }
        public Settings(String mode) {
            this.mode = mode;
            this.timeout = (long) DEFAULT_TIMEOUT_SECONDS;
            this.safetyOutputSize = DEFAULT_SAFETY_OUTPUT_SIZE;
            this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        }
    }
}

