package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 本机脚本执行器：承接扩展脚本在本机的全部进程执行逻辑。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptMeta;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptService;
import com.fishsunny.assistant.utils.ProcessUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本机脚本执行器：承接扩展脚本在本机的全部进程执行逻辑。
 * <ul>
 *   <li>前台：渲染脚本体、落临时脚本文件起进程，输出边跑边落盘；超时不杀进程直接转后台；
 *       跑完未超限删临时日志返回全文，超限保留日志只回文件标记；</li>
 *   <li>显式后台：无超时、无输出上限，输出流式写入日志文件，结束经后台总线回投。</li>
 * </ul>
 * <p>
 * 脚本元数据由调用方先行解析校验，这里只负责执行与收尾；临时脚本文件的写入与清理
 * 经 {@link ExtensionScriptService} 落地，与卫星机路径共用同一套渲染规则。
 * </p>
 */
@Component
public class LocalScriptExecutor implements ScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalScriptExecutor.class);

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final ExtensionScriptService extensionScriptService;
    private final ExtensionScriptTool.Settings settings;
    private final SessionFileManager sessionFileManager;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final CommandTaskRegistry commandTaskRegistry;

    public LocalScriptExecutor(ExtensionScriptService extensionScriptService,
                               @Qualifier(ExtensionScriptTool.SETTINGS) ExtensionScriptTool.Settings settings,
                               SessionFileManager sessionFileManager,
                               BackgroundToolResponseBus backgroundToolResponseBus,
                               CommandTaskRegistry commandTaskRegistry) {
        this.extensionScriptService = extensionScriptService;
        this.settings = settings;
        this.sessionFileManager = sessionFileManager;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
        this.commandTaskRegistry = commandTaskRegistry;
    }

    @Override
    public String execute(ExtensionScriptMeta script, Map<String, Object> arguments, boolean background, String device, String sessionId) throws Exception {
        if (background) {
            return runScriptAsync(script, arguments, sessionId);
        }

        // 渲染脚本体并落临时脚本文件（占位符替换失败、类型不支持都会在这里报错）
        String scriptBody = extensionScriptService.renderScriptBody(script, arguments);
        Path tempFile = extensionScriptService.writeTempScript(scriptBody, script.getType());
        ProcessBuilder processBuilder = buildProcess(script.getType(), tempFile);
        processBuilder.redirectErrorStream(true);

        long timeout = settings.getTimeout();
        // 不限时：一次读完，用完即清理
        if (timeout < 0) {
            Process process = processBuilder.start();
            try {
                String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new ToolExecutor.ToolExecuteException(
                            "脚本执行失败，退出码：" + exitCode + "，输出：" + result);
                }
                return result;
            } finally {
                ProcessUtils.killTree(process);
                extensionScriptService.deleteTempScript(tempFile);
            }
        }

        // 有限超时：边跑边落盘，超时就直接放手
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String fileName = "script_" + timestamp + ".log";
        // 日志统一落在会话文件目录的 logs/ 下，不跟用户自己的文件混在顶层；路径约定由 OSToolKit 管理
        String logRelPath = OSToolKit.buildLogRelativePath(fileName);
        Path logFile;
        try {
            logFile = sessionFileManager.prepareSessionFile(sessionId, logRelPath);
        } catch (IOException e) {
            extensionScriptService.deleteTempScript(tempFile);
            throw new ToolExecutor.ToolExecuteException("无法创建脚本输出目录: " + e.getMessage());
        }

        Process process = processBuilder.start();
        Future<String> future = EXECUTOR_SERVICE.submit(() -> OSToolKit.readToFile(logFile, process));

        String output;
        try {
            output = future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时不杀进程：脚本可能还在正常干活，杀掉只会让它白跑一趟，
            // 还会诱使模型把同一个脚本再执行一遍，制造更严重的重复占用。
            if (!future.isDone()) {
                String taskId = handOverToBackground(future, process, script, tempFile, logFile, logRelPath, sessionId);
                StringBuilder sb = new StringBuilder();
                sb.append("脚本执行时间已超过 ").append(timeout).append(" 秒，进程没有被终止，已自动转入后台继续运行。\n")
                        .append("输出日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath)).append("\n");
                if (StringUtils.hasText(taskId)) {
                    sb.append("后台任务 ID: ").append(taskId).append("\n")
                            .append("> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束。\n");
                }
                sb.append("> 请勿重复执行这个脚本。想看进度用 file_read_tool 读取该日志文件；"
                        + "脚本结束后退出码会自动投递到本条对话。");
                return sb.toString();
            }
            output = future.get();
        }

        int exitCode = process.waitFor();
        // 收尾路径照旧走杀树：脚本可能派生过子进程，主进程退出不代表孙子也走了
        ProcessUtils.killTree(process);
        extensionScriptService.deleteTempScript(tempFile);

        if (exitCode != 0) {
            discardQuietly(logFile);
            throw new ToolExecutor.ToolExecuteException("脚本执行失败，退出码：" + exitCode + "，输出：" + output);
        }

        // 输出限制检查：超限不再报错中断，日志文件本来就有全量输出，
        // 直接给标记由 AI 自行决定是否读取
        long outputSize = output.getBytes(StandardCharsets.UTF_8).length;
        Long maxSize = settings.getMaxOutputSize();
        if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
            String marker = SessionFileManager.buildSessionMarker(logRelPath);
            return "脚本输出过大（" + ToolKit.formatSize(outputSize) + "，超过上限 " + ToolKit.formatSize(maxSize)
                    + "），已保存到会话文件：" + marker + "。如需查看完整输出，请用 file_read_tool 读取该文件。";
        }

        discardQuietly(logFile);
        return output;
    }

    /** 分平台构建脚本进程：与卫星机 ExecEngine 的启动方式保持同一套 */
    private ProcessBuilder buildProcess(String type, Path tempFile) throws ToolExecutor.ToolExecuteException {
        return switch (type) {
            case "cmd" -> IS_WINDOWS
                    ? new ProcessBuilder("cmd.exe", "/c", tempFile.toString())
                    : new ProcessBuilder("bash", tempFile.toString());
            case "powershell" -> IS_WINDOWS
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", tempFile.toString())
                    : new ProcessBuilder("pwsh", "-NoProfile", "-File", tempFile.toString());
            case "python" -> IS_WINDOWS
                    ? new ProcessBuilder("python", tempFile.toString())
                    : new ProcessBuilder("python3", tempFile.toString());
            case "bash" -> new ProcessBuilder("bash", tempFile.toString());
            default -> throw new ToolExecutor.ToolExecuteException(
                    "不支持的脚本类型: " + type + "，支持的类型: cmd, powershell, python, bash");
        };
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
     * 前台脚本超时后把收尾交给后台：等读取任务跑完（代表输出已读尽、进程退出），
     * 再清理临时脚本并投递退出码。全程不 destroy、不 cancel。
     * <p>
     * 从超时那一刻起这个脚本就算「后台任务」了，同步登记进 {@link CommandTaskRegistry}，
     * 面板上立刻能看到进度、也能被手动结束。
     *
     * @param future     读取输出的任务
     * @param process    仍在运行的子进程
     * @param script     脚本元数据
     * @param tempFile   临时脚本文件
     * @param logFile    输出日志文件
     * @param logRelPath 日志相对会话文件目录的路径（{@code logs/xxx.log}）
     * @param sessionId  会话 id
     */
    private String handOverToBackground(Future<String> future, Process process, ExtensionScriptMeta script,
                                        Path tempFile, Path logFile, String logRelPath, String sessionId) {
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.register(
                sessionId, CommandTaskRegistry.TaskType.SCRIPT, ExtensionScriptService.scriptTitle(script), logRelPath, process);
        // 同步路径上取好 id：既回给模型拿去 wait，也供异步收尾摘任务
        final String taskId = task == null ? null : task.getId();
        EXECUTOR_SERVICE.submit(() -> {
            Exception error = null;
            int exitCode = 0;
            try {
                future.get();
                exitCode = process.waitFor();
            } catch (Exception e) {
                error = e;
            } finally {
                ProcessUtils.killTree(process);
                extensionScriptService.deleteTempScript(tempFile);
                // 被手动结束的任务已经提前摘过一次，这里再摘是幂等空操作
                commandTaskRegistry.finish(taskId);
            }
            boolean killed = task != null && task.getKilled().get();
            Integer finalExitCode = error == null ? exitCode : null;
            String failure = error == null ? null : error.getMessage();
            if (killed) {
                OSToolKit.appendFooter(logFile, "脚本已被用户手动结束");
            } else if (failure != null) {
                OSToolKit.appendFooter(logFile, "脚本执行异常: " + failure);
            } else {
                OSToolKit.appendFooter(logFile, "脚本执行完成，退出码: " + finalExitCode);
            }
            notifyBackgroundFinish(sessionId, script, logFile, logRelPath, finalExitCode, failure, killed);
            return null;
        });
        return taskId;
    }

    /**
     * 后台执行脚本：将脚本放入守护线程执行，输出以追加模式写入 session/file 目录。
     * <p>
     * 后台模式特点（与命令后台执行对齐）：
     * <ul>
     *   <li>无超时限制 —— 脚本可以运行任意长时间</li>
     *   <li>无输出大小限制 —— 输出直接写入文件，不经过内存缓存</li>
     *   <li>输出实时写入 —— 使用流式读取，每读取到数据立即 flush 到文件</li>
     *   <li>返回文件路径 —— AI 可通过 session_file_tool 或 file_read_tool 查看输出</li>
     * </ul>
     * </p>
     *
     * @param script    脚本元数据
     * @param arguments 脚本参数
     * @param sessionId 会话 ID，用于确定日志文件目录
     * @return 包含日志文件路径与后台任务 ID 的提示文本
     */
    private String runScriptAsync(ExtensionScriptMeta script, Map<String, Object> arguments, String sessionId) throws Exception {
        // 1. 同步准备脚本（渲染、写临时文件、构建进程），错误即时反馈
        String scriptBody = extensionScriptService.renderScriptBody(script, arguments);
        Path tempFile = extensionScriptService.writeTempScript(scriptBody, script.getType());
        ProcessBuilder processBuilder = buildProcess(script.getType(), tempFile);
        processBuilder.redirectErrorStream(true);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String fileName = "script_" + timestamp + ".log";
        // 日志统一落在会话文件目录的 logs/ 下，不跟用户自己的文件混在顶层；路径约定由 OSToolKit 管理
        String logRelPath = OSToolKit.buildLogRelativePath(fileName);
        Path logFile;
        try {
            logFile = sessionFileManager.prepareSessionFile(sessionId, logRelPath);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建后台输出目录: " + e.getMessage());
        }

        // 2. 写入日志文件头（同步）
        try (BufferedWriter headerWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            headerWriter.write("=== 后台脚本执行日志 ===");
            headerWriter.newLine();
            headerWriter.write("启动时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            headerWriter.newLine();
            headerWriter.write("脚本: " + script.getName() + " (" + script.getType() + ")");
            headerWriter.newLine();
            headerWriter.write("脚本文件: " + script.getFilePath());
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

        // 3. 启动进程与登记都同步完成，返回文案时 id 一定已在登记表里，
        //    模型紧接着用 wait_flow_tool 的 eventId 等它不会撞上「还没登记」被误判成已完成。
        Process process;
        try {
            process = processBuilder.start();
        } catch (Exception e) {
            extensionScriptService.deleteTempScript(tempFile);
            throw new ToolExecutor.ToolExecuteException("无法启动后台脚本: " + e.getMessage());
        }
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.register(sessionId, CommandTaskRegistry.TaskType.SCRIPT,
                ExtensionScriptService.scriptTitle(script), logRelPath, process);
        final String taskId = task == null ? null : task.getId();

        // 4. 守护线程只负责流式写日志、等退出、收尾投递
        Thread thread = new Thread(() -> runScriptToFile(script, tempFile, logFile, logRelPath, sessionId, process, task),
                "script-executor-" + timestamp);
        thread.setDaemon(true);
        thread.start();

        StringBuilder sb = new StringBuilder("脚本已在后台启动执行。\n");
        if (StringUtils.hasText(taskId)) {
            sb.append("后台任务 ID: ").append(taskId).append("\n");
        }
        sb.append("输出日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath)).append("\n")
                .append("> 提示：使用 file_read_tool 读取日志文件内容查看脚本输出。")
                .append("脚本执行完成后，日志末尾会写入退出码和结束时间。\n");
        if (StringUtils.hasText(taskId)) {
            sb.append("> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束；\n");
        }
        sb.append("> 脚本执行完成后，结果会自动投递到本条对话，无需原地等待，可以先去处理别的事情。");
        return sb.toString();
    }

    /**
     * 在后台执行已准备的脚本，将输出流式写入日志文件，结束后写入退出码和结束时间，
     * 并把收尾结果投递到后台总线（等下一次「有消息落地」的时机挂进对话）。
     *
     * @param sessionId 启动后台任务时捕获的会话 id（异步线程里上下文早已出栈，只能靠调用方捕获）
     */
    private void runScriptToFile(ExtensionScriptMeta script, Path tempFile, Path logFile, String logRelPath, String sessionId,
                                 Process process, CommandTaskRegistry.RunningTask task) {
        Integer exitCode = null;
        String failure = null;
        final String taskId = task == null ? null : task.getId();
        try {
            OSToolKit.writeLog(logFile, process);
            exitCode = process.waitFor();
        } catch (Exception e) {
            failure = e.getMessage();
            log.error("Error executing script in background: {}", e.getMessage());
        } finally {
            ProcessUtils.killTree(process);
            extensionScriptService.deleteTempScript(tempFile);
            // 被手动结束的任务已经提前摘过一次，这里再摘是幂等空操作
            commandTaskRegistry.finish(taskId);
        }

        boolean killed = task != null && task.getKilled().get();
        if (killed) {
            OSToolKit.appendFooter(logFile, "脚本已被用户手动结束");
        } else if (failure != null) {
            OSToolKit.appendFooter(logFile, "脚本执行异常: " + failure);
        } else {
            OSToolKit.appendFooter(logFile, "脚本执行完成，退出码: " + exitCode);
        }
        // 成功或异常都要收尾投递
        notifyBackgroundFinish(sessionId, script, logFile, logRelPath, exitCode, failure, killed);
    }

    /**
     * 后台脚本收尾后投递结果到后台总线。
     * <p>
     * 投递内容只写「退出码/异常 + 脚本标识 + 日志路径」，刻意不带日志尾部：脚本输出单行长度不可控，
     * 一行几十万字符的日志会把整段对话的上下文直接撑爆，要看内容让模型自己去读文件。
     *
     * @param sessionId  启动后台任务时捕获的会话 id
     * @param script     脚本元数据（取名称与类型）
     * @param logFile    输出日志文件
     * @param logRelPath 日志相对会话文件目录的路径（{@code logs/xxx.log}）
     * @param exitCode   退出码；执行异常时为 null
     * @param failure    异常信息；正常结束时为 null
     * @param killed     是否由用户从面板手动结束
     */
    private void notifyBackgroundFinish(String sessionId, ExtensionScriptMeta script, Path logFile, String logRelPath,
                                        Integer exitCode, String failure, boolean killed) {
        StringBuilder text = new StringBuilder();
        if (killed) {
            // 手动结束和「自己失败」必须区分开：退出码 1 那种写法会让人以为是脚本本身出了问题
            text.append("脚本已被用户手动结束");
        } else if (failure != null) {
            text.append("脚本执行异常: ").append(failure);
        } else {
            text.append("脚本执行完成，退出码: ").append(exitCode);
        }
        text.append("\n脚本: ").append(ExtensionScriptService.scriptTitle(script));
        text.append("\n日志文件: ").append(SessionFileManager.buildSessionMarker(logRelPath));

        backgroundToolResponseBus.post(sessionId, ExtensionScriptTool.NAME, text.toString());
    }
}
