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
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.engine.tool.service.security.ReviewResult;
import com.fishsunny.assistant.utils.ProcessUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

    /** auto 模式下的安全命令白名单（Windows PowerShell），命中则跳过 AI 判断直接执行 */
    private static final List<String> DEFAULT_WHITE_LIST_WINDOWS = List.of(
            // 目录/文件查看（含常见别名）
            "get-childitem", "gci", "dir", "ls",
            "get-item", "gi",
            "get-content", "gc", "cat", "type",
            "get-location", "pwd", "gl",
            "set-location", "cd", "sl",
            "test-path", "resolve-path", "split-path", "join-path",
            // 输出/帮助
            "write-output", "write", "write-host", "echo",
            "get-help", "help", "man",
            "get-command", "gcm", "get-alias", "gal",
            // 进程/服务/系统信息
            "get-process", "gps", "ps", "tasklist",
            "get-service", "gsv",
            "get-date", "date",
            "get-host", "hostname",
            "get-computerinfo", "get-volume", "get-psdrive",
            "whoami", "ipconfig", "nslookup", "netstat", "tree",
            // 文本/管道处理
            "select-string", "sls", "findstr",
            "select-object", "select",
            "sort-object", "sort",
            "measure-object", "measure",
            "format-table", "ft", "format-list", "fl", "out-string",
            "where-object", "where", "foreach-object", "foreach",
            "convertto-json", "convertfrom-json", "convertto-csv", "convertfrom-csv",
            "compare-object", "diff",
            "get-member", "gm",
            // 清屏
            "clear-host", "clear", "cls"
    );

    /** auto 模式下的危险命令黑名单（Windows PowerShell），命中则强制询问用户 */
    private static final List<String> DEFAULT_BLACK_LIST_WINDOWS = List.of(
            // 删除文件/目录（递归或强制，含别名）
            "remove-item -recurse", "remove-item -force", "remove-item -r",
            "rm -r", "rm -rf", "rm -recurse", "rm -force",
            "ri -r", "ri -recurse", "ri -force",
            "rmdir -recurse", "rd -recurse",
            "del -recurse", "del -force",
            "erase -recurse", "erase -force",
            "clear-content", "clc",
            // 磁盘/分区
            "format-volume", "format", "clear-disk", "diskpart",
            "initialize-disk", "remove-partition", "new-partition",
            // 注册表/系统配置
            "set-itemproperty", "new-itemproperty", "remove-itemproperty",
            "reg add", "reg delete",
            "bcdedit", "set-executionpolicy",
            // 关机/重启
            "shutdown", "stop-computer", "restart-computer",
            // 权限/账户
            "icacls", "takeown", "cacls",
            "remove-localuser", "disable-localuser", "remove-aduser",
            // 服务
            "sc delete", "sc.exe delete", "set-service", "stop-service",
            // 强制结束进程
            "stop-process -force", "taskkill /f", "taskkill /pid", "kill -9",
            "remove-ciminstance", "remove-wmiobject",
            // 任意代码执行
            "invoke-expression", "iex",
            // 其他破坏性操作
            "netsh", "wmic delete", "vssadmin delete", "cipher /w"
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
        return IS_WINDOWS ? "powershell.exe" : "bash";
    }

    /**
     * PowerShell 退出码修正后缀。
     * <p>
     * Windows PowerShell 的退出码规则与 cmd/bash 不同：原生程序失败时它只回 1（真实退出码丢失），
     * 且一旦后续命令成功，退出码又会被重置为 0（前面的失败被掩盖）。追加这段守卫，把最后一个
     * 原生命令的真实退出码透传出来；若最后一步是报错的内置命令（$LASTEXITCODE 未设置但 $? 为假），
     * 则以 1 收场。命令自身若以 exit 结尾，守卫不可达，也不影响。
     * <p>
     * 前缀必须是换行而不是 "; "：PowerShell 的 # 注释一直吃到行尾，命令末尾只要带个注释，
     * 用分号拼在同一行的守卫就会被整段注释掉（实测 cmd /c exit 7 # comment 只回笼统的 1）。
     * 独占一行则无论前面有多少注释都吞不到它。
     */
    private static final String POWERSHELL_EXIT_GUARD =
            "\nif ($LASTEXITCODE) { exit $LASTEXITCODE } elseif (-not $?) { exit 1 }";

    /**
     * 构造执行命令的 ProcessBuilder，自动拼接当前平台的 Shell 前缀。
     * PowerShell 前缀比 bash 长，不能再按固定两段拼接；Windows 下先追加退出码守卫，
     * 再把整个脚本编码成 Base64 交给 -EncodedCommand（见 {@link #WINDOWS_SHELL}）。
     */
    private static ProcessBuilder newProcessBuilder(String command) {
        String[] shell = getShell();
        List<String> commandLine = new ArrayList<>(shell.length + 1);
        commandLine.addAll(Arrays.asList(shell));
        if (IS_WINDOWS) {
            String script = command + POWERSHELL_EXIT_GUARD;
            // -EncodedCommand 要求 UTF-16LE；输出侧仍按 UTF-8 读，中文一并安全
            commandLine.add(Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        } else {
            commandLine.add(command);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    /** 获取当前平台操作系统名称 */
    private static String getOsName() {
        return IS_WINDOWS ? "Windows" : "Linux";
    }

    /** 默认命令执行超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** 默认安全输出大小限制（字节） */
    private static final long DEFAULT_SAFETY_OUTPUT_SIZE = 8192L;

    /** 默认最大输出大小限制（字节） */
    private static final long DEFAULT_MAX_OUTPUT_SIZE = 32768L;

    /** 当前操作系统是否为 Windows */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * Windows 命令行前缀（PowerShell）。
     * <p>
     * 脚本不直接挂在命令行上，而是编码成 Base64 交给 -EncodedCommand。原因：
     * ProcessBuilder 往 Windows 命令行填参数时走的是 CRT 那一套转义规则（参数内双引号变 \"），
     * 而 powershell.exe 解析 -Command 用的是自己那套规则，两边对不上，命令里的双引号会被吃掉——
     * 表现就是 Write-Output "a b" 被当成两个参数、Get-Content "C:\Program Files\x" 报找不到路径。
     * -EncodedCommand 让命令行里只剩一串 Base64，引号、转义、编码三个问题一次绕开。
     * -NoProfile 则是避免用户配置文件污染输出。
     */
    private static final String[] WINDOWS_SHELL = {"powershell.exe", "-NoProfile", "-EncodedCommand"};

    /** Linux/Mac 命令行前缀 */
    private static final String[] LINUX_SHELL = {"bash", "-c"};

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final SecurityService securityService;
    private final SessionFileManager sessionFileManager;
    private final BackgroundToolResponseBus backgroundToolResponseBus;

    public CommandTool(ObjectMapper objectMapper,
                       @Qualifier(SETTINGS) Settings settings,
                       SecurityService securityService,
                       SessionFileManager sessionFileManager,
                       BackgroundToolResponseBus backgroundToolResponseBus
                       ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.securityService = securityService;
        this.sessionFileManager = sessionFileManager;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
    }

    @Override
    @ToolIncludeContext(key = {"session", "chatSession"}, type = {WebSocketSession.class, ChatSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
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

            // ======================== 后台执行模式 ========================
            if (Boolean.TRUE.equals(arguments.getBackground())) {
                return executeInBackground(arguments.getCommand(), context);
            }

            // ======================== 前台执行模式 ========================
            ProcessBuilder processBuilder = newProcessBuilder(arguments.getCommand());
            Process process = processBuilder.start();

            Future<String> future = EXECUTOR_SERVICE.submit(() -> {
                byte[] bytes = process.getInputStream().readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            });

            String result;
            try {
                result = future.get(settings.getTimeout(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 读输出的任务还挂在管道上，先取消
                future.cancel(true);
                // 连子孙一起杀：只 destroy 父进程的话，powershell.exe 派生的子进程会继续跑完，
                // 于是出现「工具已经报超时，命令却还在后台改文件」的怪象
                ProcessUtils.killTree(process);
                throw new ToolExecutor.ToolExecuteException("命令执行超时（" + settings.getTimeout() + "秒），已终止该命令及其子进程。如果该命令打开了一个进程用于运行GUI等，那么这个报错是正常。");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ToolExecutor.ToolExecuteException("命令执行失败，退出码：" + exitCode + "，输出：" + result);
            }

            long outputSize = result.getBytes(StandardCharsets.UTF_8).length;

            // 1. 硬限制检查：maxOutputSize 始终生效，不受 skipSafeMode 影响
            //    超限不再报错中断，而是把完整输出落盘到会话文件，只回提示，由 AI 自行决定是否读取
            Long maxSize = settings.getMaxOutputSize();
            if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
                return saveOversizeOutput(context, result, outputSize, "最大限制", maxSize);
            }

            // 2. 安全限制检查：仅在未跳过安全模式时生效
            //    与硬限制同样处理，超限落盘而非拦截
            if (!Boolean.TRUE.equals(arguments.getSkipSafeMode())) {
                Long safetySize = settings.getSafetyOutputSize();
                if (safetySize != null && safetySize > 0 && outputSize > safetySize) {
                    return saveOversizeOutput(context, result, outputSize, "安全限制", safetySize);
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), result);

        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 按 shell 的元字符（&amp;&amp;, ||, &amp;, |, ;）拆分子命令。
     * 仅当元字符位于双引号之外时才作为分隔符，避免误拆引号内的内容。
     * PowerShell 与 bash 都以 ; 作为命令分隔符，故统一处理。
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
                } else if (c == ';') {
                    // PowerShell 与 bash 都支持 ; 作为命令分隔符
                    String sub = current.toString().trim();
                    if (!sub.isEmpty()) result.add(sub);
                    current = new StringBuilder();
                } else if (c == '\n' || c == '\r') {
                    // 换行同样是命令分隔符：多行脚本 = 多条命令。不拆的话整段会被当成一个子命令，
                    // 而 matchesPattern 是从子命令开头比对的，黑名单必然漏判。
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
        String shellName = IS_WINDOWS ? "powershell" : "bash";
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
     * 命令输出超过限制时的落盘处理：把完整输出写入会话文件，返回提示而非报错中断。
     * <p>
     * 设计意图：命令本身已经执行成功，只是输出体量大。抛异常会让 AI 误以为命令失败、
     * 甚至重跑一次，成本更高。落盘后提示里只给模型侧沙箱标记（{@code sessionId:文件名}），
     * 不暴露真实会话 ID，需要时用 file_read_tool 传入该标记读取，不需要时也不占用上下文。
     * </p>
     *
     * @param context    工具上下文（取 chatSession 作为落盘会话）
     * @param result     命令的完整输出
     * @param outputSize 输出字节数
     * @param limitName  触发的是哪层限制（仅用于提示文案，如「最大限制」「安全限制」）
     * @param limitSize  该层限制的字节数
     * @return 携带落盘标记的提示响应
     */
    private ToolExecutor.ToolExecuteResponse saveOversizeOutput(Map<String, Object> context, String result,
                                                                long outputSize, String limitName, long limitSize) throws Exception {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession.getId();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "command_output_" + timestamp + ".log";

        String marker;
        try {
            marker = sessionFileManager.writeSessionFileMarker(sessionId, fileName, result.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("命令输出过大（" + ToolKit.formatSize(outputSize)
                    + "），尝试保存到会话文件时失败: " + e.getMessage());
        }

        String message = "命令输出过大（" + ToolKit.formatSize(outputSize) + "，超过" + limitName
                + " " + ToolKit.formatSize(limitSize) + "），已保存到会话文件：" + marker
                + "。如需查看完整输出，请用 file_read_tool 读取该文件。";
        return new ToolExecutor.ToolExecuteResponse(name(), message);
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
        Path logFile;
        try {
            logFile = sessionFileManager.prepareSessionFile(sessionId, fileName);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建后台输出目录: " + e.getMessage());
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
            headerWriter.write("日志文件: " + SessionFileManager.buildSessionMarker(fileName));
            headerWriter.newLine();
            headerWriter.write("=".repeat(60));
            headerWriter.newLine();
            headerWriter.newLine();
            headerWriter.flush();
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法写入后台日志文件 [" + SessionFileManager.buildSessionMarker(fileName) + "]: " + e.getMessage());
        }


        // 在守护线程中执行命令，流式写入输出（直接写字节，避免编码边界问题）
        Thread backgroundThread = new Thread(() -> {
            Integer exitCode = null;
            String failure = null;
            try {
                ProcessBuilder processBuilder = newProcessBuilder(command);
                Process process = processBuilder.start();

                OSToolKit.writeLog(logFile, process);
                exitCode = process.waitFor();

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
                failure = e.getMessage();
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
            // 成功或异常都要收尾投递：等下一次「有消息落地」的时机挂进对话
            notifyBackgroundFinish(sessionId, command, logFile, exitCode, failure);
        }, "command-bg-" + timestamp);

        backgroundThread.setDaemon(true);
        backgroundThread.start();

        String message = "命令已在后台启动执行。\n"
                + "输出日志文件: " + SessionFileManager.buildSessionMarker(fileName) + "\n"
                + "> 提示：使用 file_read_tool 读取日志文件内容查看命令输出。"
                + "命令执行完成后，日志末尾会写入退出码和结束时间。\n"
                + "> 命令执行完成后，结果会自动投递到本条对话，无需原地等待，可以先去处理别的事情。";

        return new ToolExecutor.ToolExecuteResponse(name(), message);
    }

    /**
     * 后台命令收尾后投递结果到后台总线。
     * <p>
     * 投递内容 = 退出码/异常 + 命令原文 + 日志路径 + 日志尾部：总线会在下一次「有消息落地」的
     * 时机把它作为追加文本块挂进对话，模型和用户不用再主动翻日志就知道后台跑完了。
     *
     * @param sessionId 启动后台任务时捕获的会话 id（异步线程里 context 早已出栈，只能在此前捕获）
     * @param command   原始命令
     * @param logFile   输出日志文件
     * @param exitCode  退出码；执行异常时为 null
     * @param failure   异常信息；正常结束时为 null
     */
    private void notifyBackgroundFinish(String sessionId, String command, Path logFile,
                                        Integer exitCode, String failure) {
        StringBuilder text = new StringBuilder();
        if (failure != null) {
            text.append("命令执行异常: ").append(failure);
        } else {
            text.append("命令执行完成，退出码: ").append(exitCode);
        }
        text.append("\n命令: ").append(command);
        text.append("\n日志文件: ").append(SessionFileManager.buildSessionMarker(logFile.getFileName().toString()));

        backgroundToolResponseBus.post(sessionId, name(), text.toString());
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
        public Settings setWhiteList(List<String> whiteList) {
            this.whiteList = whiteList == null ? new ArrayList<>() : whiteList;
            return this;
        }

        /** auto 模式下的黑名单，命中则强制询问用户 */
        private List<String> blackList;
        public Settings setBlackList(List<String> blackList) {
            this.blackList = blackList == null ? new ArrayList<>() : blackList;
            return this;
        }

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

