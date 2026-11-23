package com.fishsunny.satellite.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.satellite.network.WebSocketClient;
import com.fishsunny.satellite.protocol.CommandRequest;
import com.fishsunny.satellite.protocol.CommandResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * @Usage 文件回传引擎：把本机文件按块读出来，经 WebSocket 推给服务端。
 *
 * @Project sunnybear-assistant-satellite
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

/**
 * 文件回传引擎：prepare 校验并登记任务、回执元数据；回执帧发出后由 {@link #afterResponseSent} 开流。
 * <p>
 * 帧序与 exec 二段回执同一套规矩：命令回执帧（含文件名与总字节数）先入队，之后才由回传线程
 * 依次发送 file_chunk / file_done（或 file_error），因此服务端收到回执时数据帧一定还没开始，
 * 可以在回执后安心打开落盘文件。
 * </p>
 * <p>
 * 传输中不占用等待窗口：文件多大都一次性推完，服务端按自己的超时设置决定什么时候转为后台。
 * 取消走同一个 cancel_task：打标记后回传线程停下并补一条 file_error，服务端据它收尾。
 * </p>
 */
public class DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(DownloadEngine.class);

    /** 单帧分块字节数：base64 后约 350KB，远低于服务端 50MB 的文本帧上限 */
    private static final int DEFAULT_CHUNK_SIZE = 256 * 1024;

    /** 分块字节数的上限，防止服务端传入夸张值把内存顶穿 */
    private static final int MAX_CHUNK_SIZE = 4 * 1024 * 1024;

    /** 待发送队列水位（字节）：超过就歇一下等网络消化，防止大文件把整个文件堆进内存 */
    private static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

    private final String deviceId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 事件通道，App 里 wsClient 构造完成后经 {@link #attach} 注入 */
    private volatile WebSocketClient wsClient;

    /** taskId → 回传任务 */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 回传线程池：每个下载任务一条线程，读盘与发包都在这里串行完成，保证帧序 */
    private final ExecutorService senders = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "satellite-download");
        t.setDaemon(true);
        return t;
    });

    public DownloadEngine(String deviceId) {
        this.deviceId = deviceId;
    }

    /** 注入事件通道（与 WebSocketClient 互相持有，main 里构造完 wsClient 后调用）。 */
    public void attach(WebSocketClient wsClient) {
        this.wsClient = wsClient;
    }

    /**
     * 校验远端文件并登记任务，返回回执元数据；真正的数据发送要等回执帧发出后触发。
     *
     * @throws IOException 参数缺失、文件不存在或不是普通文件
     */
    public Map<String, Object> prepare(CommandRequest.Params p) throws IOException {
        String taskId = p.taskId;
        if (taskId == null || taskId.isEmpty()) {
            throw new IOException("参数 taskId 不能为空");
        }
        if (p.remotePath == null || p.remotePath.isEmpty()) {
            throw new IOException("参数 remotePath 不能为空");
        }
        Path file = Paths.get(p.remotePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("远端文件不存在或不是普通文件: " + p.remotePath);
        }
        long totalSize = Files.size(file);

        int chunkSize = p.chunkSize != null && p.chunkSize > 0 ? p.chunkSize : DEFAULT_CHUNK_SIZE;
        chunkSize = Math.min(chunkSize, MAX_CHUNK_SIZE);

        tasks.put(taskId, new Task(taskId, file, totalSize, chunkSize));
        log.info("文件回传任务已登记: taskId={}, file={}, size={}B", taskId, file, totalSize);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskId", taskId);
        meta.put("fileName", file.getFileName().toString());
        meta.put("totalSize", totalSize);
        return meta;
    }

    /** 回执帧发出后启动回传线程：此后发出的 file_chunk 必然排在回执帧之后。 */
    public void afterResponseSent(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        Task task = tasks.get(taskId);
        if (task != null) {
            senders.submit(() -> stream(task));
        }
    }

    /**
     * 终止指定回传任务。
     *
     * @return true 表示任务存在且已打上取消标记；false 表示任务不存在（可能已传完）
     */
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        Task task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        task.cancelled = true;
        log.info("文件回传任务已被服务端取消: taskId={}", taskId);
        return true;
    }

    // ==================== 回传 ====================

    private void stream(Task task) {
        long sent = 0;
        try (FileChannel channel = FileChannel.open(task.file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(task.chunkSize);
            while (!task.cancelled) {
                if (!awaitQueueDrain(task)) {
                    break;
                }
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                byte[] data = new byte[read];
                buffer.flip();
                buffer.get(data);
                send(CommandResponse.fileChunk(deviceId, task.taskId, Base64.getEncoder().encodeToString(data)));
                sent += read;
            }

            if (task.cancelled) {
                send(CommandResponse.fileError(deviceId, task.taskId, "文件回传已被服务端取消"));
            } else if (sent != task.totalSize) {
                send(CommandResponse.fileError(deviceId, task.taskId,
                        "文件在回传过程中发生变化（登记时 " + task.totalSize + " 字节，实际读出 " + sent + " 字节）"));
            } else {
                send(CommandResponse.fileDone(deviceId, task.taskId, sent));
                log.info("文件回传完成: taskId={}, size={}B", task.taskId, sent);
            }
        } catch (Exception e) {
            log.error("文件回传失败: taskId={}, error={}", task.taskId, e.getMessage());
            send(CommandResponse.fileError(deviceId, task.taskId, "读取文件失败: " + e.getMessage()));
        } finally {
            tasks.remove(task.taskId);
        }
    }

    /**
     * 排队水位控制：待发送队列超过 {@link #MAX_QUEUED_BYTES} 就歇一下等网络消化。
     * <p>不做这一层，读盘速度快过网络时整份文件会先堆进 OkHttp 的发送队列，
     * 大文件足以把卫星机的堆顶穿。</p>
     *
     * @return false 表示线程被中断，调用方应当收尾退出
     */
    private boolean awaitQueueDrain(Task task) {
        WebSocketClient client = this.wsClient;
        if (client == null) {
            return true;
        }
        while (!task.cancelled && client.queueSize() > MAX_QUEUED_BYTES) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void send(CommandResponse event) {
        WebSocketClient client = this.wsClient;
        if (client == null) {
            log.error("事件通道未接入，文件帧丢弃: taskId={}", event.taskId);
            return;
        }
        try {
            client.sendEvent(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("文件帧发送失败: taskId={}, error={}", event.taskId, e.getMessage());
        }
    }

    /** 一个正在回传的文件 */
    private static final class Task {

        private final String taskId;
        private final Path file;
        private final long totalSize;
        private final int chunkSize;

        /** 服务端 cancel_task 打上的取消标记，回传线程每帧前都检查一次 */
        private volatile boolean cancelled;

        private Task(String taskId, Path file, long totalSize, int chunkSize) {
            this.taskId = taskId;
            this.file = file;
            this.totalSize = totalSize;
            this.chunkSize = chunkSize;
        }
    }
}
