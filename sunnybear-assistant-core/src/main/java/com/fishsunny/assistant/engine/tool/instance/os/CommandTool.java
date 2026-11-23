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
import com.fishsunny.assistant.engine.tool.instance.os.executor.CommandExecutor;
import com.fishsunny.assistant.engine.tool.instance.os.executor.LocalCommandExecutor;
import com.fishsunny.assistant.engine.tool.instance.os.executor.SatelliteCommandExecutor;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.engine.tool.service.security.ReviewResult;
import com.fishsunny.assistant.satellite.service.SatelliteBridgeService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行工具
 * 要求 dependency 参数传入一个 WebSocketSession 对象
 * <p>
 * 只负责安全闸门（黑白名单 / AI 审查 / 用户确认）与执行路由：
 * 本机执行细节归 {@link LocalCommandExecutor}，卫星机执行细节归 {@link SatelliteCommandExecutor}。
 * </p>
 */
@ToolKitComponent(OSToolKit.class)
@ConditionalOnExpression("${engine.tool.os.enable:true} && ${engine.tool.os.command.enable:true}")
public class CommandTool implements ToolHandler {

    public static final String AUTO = "auto";
    public static final String ALWAYS_ASKED = "alwaysAsked";
    public static final String NEVER_ASKED = "neverAsked";
    public static final String ALWAYS_REJECT_DANGER = "alwaysRejectDanger";

    public static final String NAME = "command_tool";
    public static final String SETTINGS = "command_tool_settings";

    /** 默认命令执行超时时间（秒）：Settings 默认值与卫星机等待窗口兜底共用 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** 默认最大输出大小限制（字节） */
    private static final long DEFAULT_MAX_OUTPUT_SIZE = 32768L;

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

    /** 当前操作系统是否为 Windows（splitCommands 判断 ; 是否为命令分隔符用） */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** 获取当前平台的默认白名单 */
    private static List<String> getDefaultWhiteList() {
        return IS_WINDOWS ? DEFAULT_WHITE_LIST_WINDOWS : DEFAULT_WHITE_LIST_LINUX;
    }

    /** 获取当前平台的默认黑名单 */
    private static List<String> getDefaultBlackList() {
        return IS_WINDOWS ? DEFAULT_BLACK_LIST_WINDOWS : DEFAULT_BLACK_LIST_LINUX;
    }

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final SecurityService securityService;
    private final SatelliteBridgeService satelliteBridgeService;
    private final LocalCommandExecutor localCommandExecutor;
    private final SatelliteCommandExecutor satelliteCommandExecutor;

    public CommandTool(ObjectMapper objectMapper,
                       @Qualifier(SETTINGS) Settings settings,
                       SecurityService securityService,
                       SatelliteBridgeService satelliteBridgeService,
                       LocalCommandExecutor localCommandExecutor,
                       SatelliteCommandExecutor satelliteCommandExecutor
                       ) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.securityService = securityService;
        this.satelliteBridgeService = satelliteBridgeService;
        this.localCommandExecutor = localCommandExecutor;
        this.satelliteCommandExecutor = satelliteCommandExecutor;
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

            // ======================== 执行分流 ========================
            // 安全闸门（黑白名单 / AI 审查 / 用户确认）已按同一标准过完；
            // 指定 device 时命令经卫星桥接在该机器上执行，本机路径不受影响
            ChatSession chatSession = (ChatSession) context.get("chatSession");
            String sessionId = chatSession.getId();
            boolean background = Boolean.TRUE.equals(arguments.getBackground());
            CommandExecutor executor = StringUtils.hasText(arguments.getDevice())
                    ? satelliteCommandExecutor
                    : localCommandExecutor;
            String result = executor.execute(arguments.getCommand(), background, arguments.getDevice(), sessionId);
            return new ToolExecutor.ToolExecuteResponse(name(), result);

        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 按 shell 的元字符（&&, ||, &, |, ;）拆分子命令。
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
                .setDescription("在终端中执行命令。" + modeDesc + "命令超过超时时间会自动转入后台继续执行。"
                        + "转入后台时会返回一个后台任务 ID，可用 wait_flow_tool 的 eventId 等它结束。" + buildLimitDescription()
                        + " 指定 device 时命令在卫星机上以该机器的默认 shell（Windows cmd.exe /c，Linux bash -c）执行。")
                .setRequired(List.of("command"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("command", "string", "你准备执行的命令"),
                        new ToolRegister.Parameters("background", "boolean", "设为 true 后台执行（无超时限制），适合长时间任务。输出写入日志文件；返回后台任务 ID 可供 wait_flow_tool 的 eventId 等待。"),
                        new ToolRegister.Parameters("device", "string",
                                "在指定卫星机上执行，填卫星机 deviceId；缺省在本机执行。当前在线："
                                        + satelliteBridgeService.onlineDevices())
                ));
    }

    /**
     * 构建输出限制的描述信息
     */
    private String buildLimitDescription() {
        Long maxSize = settings.getMaxOutputSize();
        if (maxSize != null && maxSize > 0) {
            return "命令输出上限为 " + ToolKit.formatSize(maxSize) + "，超出部分落盘到会话文件。";
        }
        return "";
    }

    @Data
    private static class Arguments {
        private String command;
        /** 设为 true 时命令在后台执行：无超时限制、无输出大小限制，输出以追加模式写入 session/file 目录下的日志文件 */
        private Boolean background;
        /** 卫星机 deviceId：非空时命令经桥接在该卫星机上执行 */
        private String device;
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

        /** 命令执行超时时间，单位秒；超时后命令转入后台继续执行 */
        private Long timeout;
        /** 输出字节数上限（UTF-8 编码），超出部分落盘到会话文件 */
        private Long maxOutputSize;
        public Settings() {
            this.mode = "auto";
            this.timeout = (long) DEFAULT_TIMEOUT_SECONDS;
            this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        }
        public Settings(String mode) {
            this.mode = mode;
            this.timeout = (long) DEFAULT_TIMEOUT_SECONDS;
            this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        }
    }
}
