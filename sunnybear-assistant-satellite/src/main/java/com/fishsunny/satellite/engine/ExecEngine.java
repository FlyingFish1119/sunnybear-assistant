package com.fishsunny.satellite.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.satellite.dto.ExecResult;
import com.fishsunny.satellite.network.WebSocketClient;
import com.fishsunny.satellite.protocol.CommandRequest;
import com.fishsunny.satellite.protocol.CommandResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 通用进程执行引擎：起进程、输出采集、等待窗口、进程表、完成事件推送。
 * <p>
 * 只做进程管理，不含任何业务语义：
 * <ul>
 *   <li>mode=command：按本机默认 shell 执行命令串（Windows cmd.exe /c，Linux bash -c）；</li>
 *   <li>mode=script：把正文落盘 temp/ 后按 scriptType 起进程（cmd→.bat、powershell→.ps1、
 *       python→.py、bash→.sh），分平台启动方式与核心端 LocalScriptExecutor.buildProcess 一致。</li>
 * </ul>
 * 输出在内存完整采集并回传服务端，超限判断与会话日志落盘由服务端统一处理，卫星端本地不落任何日志文件。
 * 等待窗口（waitTimeoutSec）内进程结束 → 同步回执 finished=true；
 * 未结束 → 立即回执 finished=false 并把进程交给后台看护线程，回执帧发出后输出经 task_log 事件实时流式推送，
 * 结束后经 WebSocket 推送 task_done 事件（只带退出码）。
 */
public class ExecEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecEngine.class);

    /** 进程退出后等待输出流收尾的上限（毫秒）：孙子进程占住管道时不能无限等 */
    private static final long DRAIN_TIMEOUT_MS = 5000;

    /** 优雅终止后强杀兜底前的宽限（毫秒） */
    private static final long KILL_GRACE_MS = 100;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    /** 本机默认 shell（mode=command） */
    private static final String[] WINDOWS_SHELL = {"cmd.exe", "/c"};
    private static final String[] LINUX_SHELL = {"bash", "-c"};

    private final String deviceId;
    private final ObjectMapper objectMapper;

    /** 事件通道，App 里 wsClient 构造完成后经 {@link #attach} 注入 */
    private volatile WebSocketClient wsClient;

    /** 临时脚本目录（相对本进程工作目录） */
    private final Path tempDir = Paths.get("temp");

    /** taskId → 运行中的进程 */
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    /** taskId → 已转后台、等待回执帧发出后开启流式推送的输出采集器 */
    private final Map<String, OutputRecorder> pendingStreams = new ConcurrentHashMap<>();

    /** 后台看护线程池 */
    private final ExecutorService watchers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "satellite-watch");
        t.setDaemon(true);
        return t;
    });

    public ExecEngine(String deviceId) {
        this.deviceId = deviceId;
        this.objectMapper = new ObjectMapper();
    }

    /** 注入事件通道（与 WebSocketClient 互相持有，main 里构造完 wsClient 后调用）。 */
    public void attach(WebSocketClient wsClient) {
        this.wsClient = wsClient;
    }

    // ==================== exec ====================

    /**
     * 执行一条任务：起进程 → 等待窗口 → 二段回执。
     *
     * @return finished=true 表示进程已在窗口内结束（exitCode 有值）；
     *         finished=false 表示进程仍在运行，已交给后台看护线程收尾并补报 task_done
     */
    public ExecResult exec(CommandRequest.Params p) throws Exception {
        String taskId = p.taskId;
        if (taskId == null || taskId.isEmpty()) {
            throw new IOException("参数 taskId 不能为空");
        }
        String content = p.content;
        if (content == null || content.isEmpty()) {
            throw new IOException("参数 content 不能为空");
        }
        String mode = (p.mode == null || p.mode.isEmpty()) ? "command" : p.mode;
        int waitSec = (p.waitTimeoutSec == null || p.waitTimeoutSec < 0) ? 0 : p.waitTimeoutSec;

        Process process;
        Path tempScript = null;
        try {
            ProcessBuilder builder;
            if ("command".equals(mode)) {
                String[] shell = IS_WINDOWS ? WINDOWS_SHELL : LINUX_SHELL;
                builder = new ProcessBuilder(shell[0], shell[1], content);
            } else if ("script".equals(mode)) {
                if (p.scriptType == null || p.scriptType.isEmpty()) {
                    throw new IOException("mode=script 时参数 scriptType 不能为空");
                }
                tempScript = writeTempScript(taskId, p.scriptType, content);
                builder = buildScriptProcess(p.scriptType, tempScript);
            } else {
                throw new IOException("不支持的 mode: " + mode + "，支持: command, script");
            }
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (Exception e) {
            if (tempScript != null) {
                deleteQuietly(tempScript);
            }
            throw e;
        }

        processes.put(taskId, process);
        OutputRecorder recorder = new OutputRecorder(process);

        // waitTimeoutSec=0（或负数）：不等待，立即转后台
        boolean finished = waitSec > 0 && process.waitFor(waitSec, TimeUnit.SECONDS);
        if (finished) {
            int exitCode = process.exitValue();
            recorder.await(DRAIN_TIMEOUT_MS);
            processes.remove(taskId);
            killTree(process);
            if (tempScript != null) {
                deleteQuietly(tempScript);
            }
            log.info("卫星任务完成: taskId={}, exitCode={}", taskId, exitCode);
            return new ExecResult(taskId, exitCode, recorder.snapshot(), true);
        }

        // 等待窗口内未结束：立即回执（只带窗口内已凑齐的完整字符），进程交后台看护线程收尾。
        // 流式推送不在此时开启：回执帧必须先于任何 task_log / task_done 帧发出，由发送方在
        // 回执帧入队后回调 {@link #afterExecResponseSent} 开启，帧序才有保证。
        Path tempForWatcher = tempScript;
        String initialOutput = recorder.drainValidUtf8();
        pendingStreams.put(taskId, recorder);
        watchers.submit(() -> watchInBackground(taskId, process, recorder, tempForWatcher));
        log.info("卫星任务转后台: taskId={}, 等待窗口={}s", taskId, waitSec);
        return new ExecResult(taskId, null, initialOutput, false);
    }

    /**
     * exec 回执帧发出后由发送方回调：开启该任务的流式推送，后续输出经 task_log 事件实时回传服务端。
     * <p>
     * 只处理转后台的任务（前台跑完的没有登记），重复调用或任务不存在时为安全空操作。
     */
    public void afterExecResponseSent(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        OutputRecorder recorder = pendingStreams.remove(taskId);
        if (recorder != null) {
            recorder.enableStreaming(chunk -> sendTaskLog(taskId, chunk));
        }
    }

    /** 后台收尾：等进程退出 → 清进程表与临时脚本 → 推送 task_done 事件（只带退出码）。 */
    private void watchInBackground(String taskId, Process process, OutputRecorder recorder, Path tempScript) {
        Integer exitCode = null;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        recorder.await(DRAIN_TIMEOUT_MS);
        // 等回执帧已发出（流式推送已开启）再补报：保证 task_done 一定排在回执帧之后；
        // 读取线程此时已退出，所有 task_log 也都已入队，三种帧的先后顺序就此闭合。
        recorder.awaitResponseGate();
        processes.remove(taskId);
        killTree(process);
        if (tempScript != null) {
            deleteQuietly(tempScript);
        }
        log.info("卫星后台任务完成: taskId={}, exitCode={}", taskId, exitCode);
        sendTaskDone(taskId, exitCode);
    }

    // ==================== cancel_task ====================

    /**
     * 终止指定任务（杀整棵进程树）。
     *
     * @return true 表示任务存在且已对其执行终止；false 表示任务不存在（可能已自然结束）
     */
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        Process process = processes.remove(taskId);
        if (process == null) {
            return false;
        }
        killTree(process);
        log.info("卫星任务已被服务端终止: taskId={}", taskId);
        return true;
    }

    // ==================== 事件 ====================

    private void sendTaskLog(String taskId, String chunk) {
        WebSocketClient client = this.wsClient;
        if (client == null) {
            log.error("事件通道未接入，task_log 丢弃: taskId={}", taskId);
            return;
        }
        try {
            client.sendEvent(toJson(CommandResponse.taskLog(deviceId, taskId, chunk)));
        } catch (Exception e) {
            log.error("task_log 事件发送失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void sendTaskDone(String taskId, Integer exitCode) {
        WebSocketClient client = this.wsClient;
        if (client == null) {
            log.error("事件通道未接入，task_done 丢弃: taskId={}", taskId);
            return;
        }
        try {
            CommandResponse event = CommandResponse.taskDone(deviceId, taskId, exitCode);
            client.sendEvent(toJson(event));
        } catch (Exception e) {
            log.error("task_done 事件发送失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    // ==================== 进程构造 ====================

    /** 与核心端 LocalScriptExecutor.buildProcess 同一套分平台启动方式。 */
    private ProcessBuilder buildScriptProcess(String scriptType, Path scriptFile) throws IOException {
        return switch (scriptType) {
            case "cmd" -> IS_WINDOWS
                    ? new ProcessBuilder("cmd.exe", "/c", scriptFile.toString())
                    : new ProcessBuilder("bash", scriptFile.toString());
            case "powershell" -> IS_WINDOWS
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString())
                    : new ProcessBuilder("pwsh", "-NoProfile", "-File", scriptFile.toString());
            case "python" -> IS_WINDOWS
                    ? new ProcessBuilder("python", scriptFile.toString())
                    : new ProcessBuilder("python3", scriptFile.toString());
            case "bash" -> new ProcessBuilder("bash", scriptFile.toString());
            default -> throw new IOException(
                    "不支持的脚本类型: " + scriptType + "，支持的类型: cmd, powershell, python, bash");
        };
    }

    private Path writeTempScript(String taskId, String scriptType, String content) throws IOException {
        String ext = switch (scriptType.toLowerCase()) {
            case "cmd" -> ".bat";
            case "powershell" -> ".ps1";
            case "python" -> ".py";
            case "bash" -> ".sh";
            default -> throw new IOException(
                    "不支持的脚本类型: " + scriptType + "，支持的类型: cmd, powershell, python, bash");
        };
        Files.createDirectories(tempDir);
        Path file = tempDir.resolve(taskId + ext);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ==================== 进程树终止 ====================

    /**
     * 杀整棵进程树：先对全部后代做优雅 destroy、再 destroy 本体；
     * 宽限后对仍存活的进程 destroyForcibly 兜底。
     * <p>
     * 先给子孙拍快照再动手：父进程一旦死掉，再去遍历它的 descendants 就什么都查不到了，
     * 剩下那批子进程会变成没人管的孤儿。对已退出的进程是安全空操作。
     */
    private static void killTree(Process process) {
        ProcessHandle.of(process.pid()).ifPresent(self -> {
            List<ProcessHandle> descendants;
            try {
                descendants = self.descendants().toList();
            } catch (Exception e) {
                // 进程可能刚好退出，拿不到子孙列表不影响杀本体
                descendants = List.of();
            }
            descendants.forEach(ExecEngine::destroyQuietly);
            destroyQuietly(self);
            try {
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            descendants.forEach(ExecEngine::destroyForciblyQuietly);
            destroyForciblyQuietly(self);
        });
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            handle.destroy();
        } catch (Exception ignored) {
            // 个别平台对已退出进程的 destroy 可能抛异常，忽略
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响任务本身
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    // ==================== 输出采集 ====================

    /**
     * 输出采集器：读进程合并流（stdout + stderr），先在内存累积，任务转后台后平滑切换为流式推送。
     * <ul>
     *   <li>前台（等待窗口内结束）：全程只在内存累积，结束取完整快照随回执返回；</li>
     *   <li>转后台：先经 {@link #drainValidUtf8} 取走已凑齐的完整字符随回执下发，回执帧发出后
     *       由 {@link #enableStreaming} 开启推送——之后每读到一块就按 UTF-8 字符边界切出完整
     *       部分推给消费者，末尾未凑齐的残余字节（最多 3 个）留在缓冲里等下一块拼齐。</li>
     * </ul>
     * 切分与推送全部在 {@code synchronized (buffer)} 锁内完成：推送顺序与字节顺序严格一致。
     */
    private static final class OutputRecorder {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread reader;

        /** 流式推送消费者；为 null 时输出只在内存累积，非 null 后每凑齐一段完整字符即推送 */
        private Consumer<String> streamConsumer;

        /** 回执帧发出门闩：流式推送开启时放开，task_done 补报前等它，保证帧序 */
        private final CountDownLatch responseGate = new CountDownLatch(1);

        OutputRecorder(Process process) {
            this.reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        synchronized (buffer) {
                            buffer.write(chunk, 0, n);
                            if (streamConsumer != null) {
                                pushValidUtf8();
                            }
                        }
                    }
                    // 流结束：把最后攒下的残余字节整段推干净
                    synchronized (buffer) {
                        if (streamConsumer != null && buffer.size() > 0) {
                            streamConsumer.accept(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                            buffer.reset();
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被终止时流会异常关闭，已采集的部分不受影响
                }
            }, "satellite-output");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        /** 锁内调用：把缓冲里凑齐的完整 UTF-8 字符推给消费者，未凑齐的残余字节留下继续攒 */
        private void pushValidUtf8() {
            byte[] all = buffer.toByteArray();
            int validLen = utf8ValidLength(all);
            if (validLen > 0) {
                streamConsumer.accept(new String(all, 0, validLen, StandardCharsets.UTF_8));
                buffer.reset();
                buffer.write(all, validLen, all.length - validLen);
            }
        }

        /** 当前已采集输出的完整快照 */
        String snapshot() {
            synchronized (buffer) {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        /** 取出当前已凑齐的完整 UTF-8 字符串；末尾未凑齐的多字节残余字节留在缓冲里 */
        String drainValidUtf8() {
            synchronized (buffer) {
                byte[] all = buffer.toByteArray();
                int validLen = utf8ValidLength(all);
                buffer.reset();
                buffer.write(all, validLen, all.length - validLen);
                return validLen > 0 ? new String(all, 0, validLen, StandardCharsets.UTF_8) : "";
            }
        }

        /** 开启流式推送（并推掉开启前新攒的输出）后放行收尾事件：调用时机在 exec 回执帧入队之后 */
        void enableStreaming(Consumer<String> consumer) {
            synchronized (buffer) {
                this.streamConsumer = consumer;
                pushValidUtf8();
            }
            responseGate.countDown();
        }

        /** 等流式推送已开启（即 exec 回执帧已在途）；task_done 补报前调用，保证完成帧排在回执帧之后 */
        void awaitResponseGate() {
            try {
                responseGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** 等采集线程收尾（进程退出后输出流可能还有缓冲残留），之后用 snapshot() 取全量 */
        void await(long timeoutMillis) {
            try {
                reader.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 计算字节数组中到最后一个完整 UTF-8 字符为止的长度。
     * <p>
     * 从末尾往回最多看 3 个续字节（{@code 10xxxxxx}）定位多字节序列的首字节；首字节声明的
     * 总字节数没凑齐时，截到该首字节之前，残余部分由调用方留在缓冲里等下一块拼齐，
     * 绝不把半个字符解码出去。
     *
     * @return 可安全按 UTF-8 解码的前缀长度；开头就是续字节时返回 0
     */
    private static int utf8ValidLength(byte[] bytes) {
        int len = bytes.length;
        int i = len - 1;
        int trail = 0;
        while (i >= 0 && trail < 3 && (bytes[i] & 0xC0) == 0x80) {
            i--;
            trail++;
        }
        if (i < 0) {
            return 0;
        }
        int lead = bytes[i] & 0xFF;
        int expected = (lead & 0x80) == 0 ? 1
                : (lead & 0xE0) == 0xC0 ? 2
                : (lead & 0xF0) == 0xE0 ? 3
                : (lead & 0xF8) == 0xF0 ? 4
                : -1;
        if (expected < 0) {
            // 非法首字节：当作未凑齐的残余留下，等流结束时整段兜底解码
            return i;
        }
        return trail + 1 >= expected ? len : i;
    }
}
