package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 本机命令执行器：前台执行（超时自动转后台）、显式后台执行与后台完成回投。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本机命令执行器：承接命令在本机的全部进程执行逻辑。
 * <ul>
 *   <li>前台：边跑边把输出实时落盘，超时不杀进程直接转后台；跑完未超限删临时日志返回全文，
 *       超限保留日志只回文件标记；</li>
 *   <li>显式后台：无超时、无输出上限，输出流式写入日志文件，结束经后台总线回投。</li>
 * </ul>
 */
@Component
public class LocalCommandExecutor implements CommandExecutor {

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** Windows 命令行前缀 */
    private static final String[] WINDOWS_SHELL = {"cmd.exe", "/c"};

    /** Linux/Mac 命令行前缀 */
    private static final String[] LINUX_SHELL = {"bash", "-c"};

    private final CommandTool.Settings settings;
    private final SessionFileManager sessionFileManager;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final CommandTaskRegistry commandTaskRegistry;

    public LocalCommandExecutor(@Qualifier(CommandTool.SETTINGS) CommandTool.Settings settings,
                                SessionFileManager sessionFileManager,
                                BackgroundToolResponseBus backgroundToolResponseBus,
                                CommandTaskRegistry commandTaskRegistry) {
        this.settings = settings;
        this.sessionFileManager = sessionFileManager;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
        this.commandTaskRegistry = commandTaskRegistry;
    }

    @Override
    public String execute(String command, boolean background, String device, String sessionId) throws Exception {
        if (background) {
            return executeInBackground(command, sessionId);
        }

        String fileName = "command_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) + ".log";
        // 日志统一落在会话文件目录的 logs/ 下，不跟用户自己的文件混在顶层；路径约定由 OSToolKit 管理
        String logRelPath = OSToolKit.buildLogRelativePath(fileName);
        Path logFile;
        try {
            logFile = sessionFileManager.prepareSessionFile(sessionId, logRelPath);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建命令输出目录: " + e.getMessage());
        }

        String[] shell = getShell();
        ProcessBuilder processBuilder = new ProcessBuilder(shell[0], shell[1], command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        // 输出一边实时落盘，一边完整读进内存。
        // 落盘使超时后可以直接把管道交给后台，不需要事后重建日志。
        Future<String> future = EXECUTOR_SERVICE.submit(() -> OSToolKit.readToFile(logFile, process));

        String output;
        try {
            output = future.get(settings.getTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时不杀进程：命令常常还在正常干活（编译、装依赖、跑服务），
            // 杀掉既丢结果，又会让 AI 误判失败而重跑一遍，制造端口/文件锁冲突。
            if (!future.isDone()) {
                String taskId = watchBackgroundFinish(future, process, sessionId, command, logFile, logRelPath);
                return buildTimeoutToBackgroundMessage(settings.getTimeout(), logRelPath, taskId);
            }
            output = future.get();
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            discardQuietly(logFile);
            throw new ToolExecutor.ToolExecuteException("命令执行失败，退出码：" + exitCode + "，输出：" + output);
        }

        // 输出限制检查：超限不再报错中断，日志文件本来就有全量输出，
        // 直接给标记由 AI 自行决定是否读取
        long outputSize = output.getBytes(StandardCharsets.UTF_8).length;
        Long maxSize = settings.getMaxOutputSize();
        if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
            String marker = SessionFileManager.buildSessionMarker(logRelPath);
            return "命令输出过大（" + ToolKit.formatSize(outputSize) + "，超过上限 " + ToolKit.formatSize(maxSize)
                    + "），已保存到会话文件：" + marker + "。如需查看完整输出，请用 file_read_tool 读取该文件。";
        }

        discardQuietly(logFile);
        return output;
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

    /**
     * 前台超时后，在后台等命令真正跑完，把退出码与日志尾部投递回对话。
     * <p>
     * 只做「等结果 + 投递」，不去碰进程：不 destroy、不 cancel。
     * 读取输出的任务跑完即代表 stdout 已关闭、进程即将或已经退出。
     * </p>
     * <p>
     * 从超时那一刻起这条命令就算「后台任务」了，同步登记进 {@link CommandTaskRegistry}，
     * 面板上立刻能看到进度、也能被手动结束。
     * </p>
     *
     * @param future     读取输出的任务
     * @param process    仍在运行的子进程
     * @param sessionId  会话 id
     * @param command    原始命令
     * @param logFile    输出日志文件
     * @param logRelPath 日志相对会话文件目录的路径（{@code logs/xxx.log}）
     */
    private String watchBackgroundFinish(Future<String> future, Process process,
                                         String sessionId, String command, Path logFile, String logRelPath) {
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.register(
                sessionId, CommandTaskRegistry.TaskType.COMMAND, command, logRelPath, process);
        // 异步收尾里要用 id 摘任务，先在此处（同步路径）取好，顺手回给模型拿去 wait
        final String taskId = task == null ? null : task.getId();
        EXECUTOR_SERVICE.submit(() -> {
            Integer exitCode = null;
            String failure = null;
            try {
                future.get();
                exitCode = process.waitFor();
            } catch (Exception e) {
                failure = e.getMessage();
            } finally {
                // 被手动结束的任务已经提前摘过一次，这里再摘是幂等空操作
                commandTaskRegistry.finish(taskId);
            }
            boolean killed = task != null && task.getKilled().get();
            if (killed) {
                OSToolKit.appendFooter(logFile, "命令已被用户手动结束");
            } else if (failure != null) {
                OSToolKit.appendFooter(logFile, "命令执行异常: " + failure);
            } else {
                OSToolKit.appendFooter(logFile, "命令执行完成，退出码: " + exitCode);
            }
            notifyBackgroundFinish(sessionId, command, logFile, logRelPath, exitCode, failure, killed);
            return null;
        });
        return taskId;
    }

    /**
     * 超时转后台时回给模型的说明。
     * <p>
     * 措辞重点是「命令仍在跑，别重跑」——超时报错最容易诱使模型把同一条命令再敲一遍，
     * 于是端口被占、文件被锁、编译互相打架。这里把状态讲清楚，并把日志路径交出去。
     * 同时把后台任务 id 交出去，模型可用 wait_flow_tool 的 eventId 等它结束。
     * </p>
     */
    private String buildTimeoutToBackgroundMessage(long timeoutSeconds, String logRelPath, String taskId) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令执行时间已超过 ").append(timeoutSeconds).append(" 秒，进程没有被终止，已自动转入后台继续运行。\n")
                .append("输出日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath)).append("\n");
        if (StringUtils.hasText(taskId)) {
            sb.append("后台任务 ID: ").append(taskId).append("\n")
                    .append("> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束。\n");
        }
        sb.append("> 请勿重复执行这条命令。想看进度用 file_read_tool 读取该日志文件；"
                + "命令结束后退出码与日志尾部会自动投递到本条对话。");
        return sb.toString();
    }

    /** 正常收尾时清掉临时日志文件；清不掉也无所谓，残留文件不影响任何逻辑 */
    private void discardQuietly(Path logFile) {
        try {
            Files.deleteIfExists(logFile);
        } catch (IOException ignored) {
            // 忽略清理失败
        }
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
     * @param command   要执行的命令
     * @param sessionId 会话 id，日志落盘与任务登记归属
     * @return 包含日志文件路径与后台任务 ID 的提示文本
     */
    private String executeInBackground(String command, String sessionId) throws Exception {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String fileName = "command_" + timestamp + ".log";
        // 日志统一落在会话文件目录的 logs/ 下，不跟用户自己的文件混在顶层；路径约定由 OSToolKit 管理
        String logRelPath = OSToolKit.buildLogRelativePath(fileName);
        Path logFile;
        try {
            logFile = sessionFileManager.prepareSessionFile(sessionId, logRelPath);
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
            headerWriter.write("日志文件: " + SessionFileManager.buildSessionMarker(logRelPath));
            headerWriter.newLine();
            headerWriter.write("=".repeat(60));
            headerWriter.newLine();
            headerWriter.newLine();
            headerWriter.flush();
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法写入后台日志文件 [" + SessionFileManager.buildSessionMarker(logRelPath) + "]: " + e.getMessage());
        }


        // 启动进程与登记都放在调用线程里同步完成：进程 start 是毫秒级、失败能当场报错，
        // 更重要的是返回文案时这个 id 一定已经在登记表里 —— 模型紧接着用 wait_flow_tool 的
        // eventId 等它，不会撞上「守护线程还没登记」而按「查不到即完成」被误判成已结束。
        // 守护线程只负责流式写日志、等退出、收尾投递。
        String[] shell = getShell();
        ProcessBuilder processBuilder = new ProcessBuilder(shell[0], shell[1], command);
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("无法启动后台命令: " + e.getMessage());
        }
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.register(
                sessionId, CommandTaskRegistry.TaskType.COMMAND, command, logRelPath, process);
        final String taskId = task == null ? null : task.getId();

        Thread backgroundThread = new Thread(() -> {
            Integer exitCode = null;
            String failure = null;
            try {
                OSToolKit.writeLog(logFile, process);
                exitCode = process.waitFor();
            } catch (Exception e) {
                failure = e.getMessage();
            } finally {
                // 被手动结束的任务已经提前摘过一次，这里再摘是幂等空操作
                commandTaskRegistry.finish(taskId);
            }

            boolean killed = task != null && task.getKilled().get();
            if (killed) {
                OSToolKit.appendFooter(logFile, "命令已被用户手动结束");
            } else if (failure != null) {
                OSToolKit.appendFooter(logFile, "命令执行异常: " + failure);
            } else {
                OSToolKit.appendFooter(logFile, "命令执行完成，退出码: " + exitCode);
            }
            // 成功或异常都要收尾投递：等下一次「有消息落地」的时机挂进对话
            notifyBackgroundFinish(sessionId, command, logFile, logRelPath, exitCode, failure, killed);
        }, "command-bg-" + timestamp);

        backgroundThread.setDaemon(true);
        backgroundThread.start();

        StringBuilder message = new StringBuilder("命令已在后台启动执行。\n");
        if (StringUtils.hasText(taskId)) {
            message.append("后台任务 ID: ").append(taskId).append("\n");
        }
        message.append("输出日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath)).append("\n")
                .append("> 提示：使用 file_read_tool 读取日志文件内容查看命令输出。")
                .append("命令执行完成后，日志末尾会写入退出码和结束时间。\n");
        if (StringUtils.hasText(taskId)) {
            message.append("> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束；\n");
        }
        message.append("> 命令执行完成后，结果会自动投递到本条对话，无需原地等待，可以先去处理别的事情。");

        return message.toString();
    }

    /**
     * 后台命令收尾后投递结果到后台总线。
     * <p>
     * 投递内容 = 退出码/异常 + 命令原文 + 日志路径 + 日志尾部：总线会在下一次「有消息落地」的
     * 时机把它作为追加文本块挂进对话，模型和用户不用再主动翻日志就知道后台跑完了。
     *
     * @param sessionId  启动后台任务时捕获的会话 id（异步线程里 context 早已出栈，只能在此前捕获）
     * @param command    原始命令
     * @param logFile    输出日志文件
     * @param logRelPath 日志相对会话文件目录的路径（{@code logs/xxx.log}）
     * @param exitCode   退出码；执行异常时为 null
     * @param failure    异常信息；正常结束时为 null
     * @param killed     是否由用户从面板手动结束
     */
    private void notifyBackgroundFinish(String sessionId, String command, Path logFile, String logRelPath,
                                        Integer exitCode, String failure, boolean killed) {
        StringBuilder text = new StringBuilder();
        if (killed) {
            // 手动结束和「自己失败」必须区分开：退出码 1 那种写法会让人以为是命令本身出了问题
            text.append("命令已被用户手动结束");
        } else if (failure != null) {
            text.append("命令执行异常: ").append(failure);
        } else {
            text.append("命令执行完成，退出码: ").append(exitCode);
        }
        text.append("\n命令: ").append(command);
        text.append("\n日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath));

        backgroundToolResponseBus.post(sessionId, CommandTool.NAME, text.toString());
    }
}
