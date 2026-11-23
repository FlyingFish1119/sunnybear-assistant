package com.fishsunny.assistant.engine.tool.service.satellite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.engine.tool.service.background.BridgeTaskChannel;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.engine.tool.service.satellite.dto.ExecResult;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 卫星机桥接服务，管理来自卫星代理 JAR 的 WebSocket 连接（端点 /satellite-bridge）。
 * <p>
 * 协议：
 * <ul>
 *   <li>注册：{"type":"register","deviceId":"...","deviceName":"..."}，按 deviceId 存连接，
 *       同 deviceId 重连顶替旧连接并关闭旧 session；</li>
 *   <li>命令（RPC）：{"id":"uuid","method":"exec|cancel_task","params":{...}} → 阻塞等待
 *       {"id","result"} / {"id","error"} 回执；</li>
 *   <li>事件（都在 WebSocket 接收线程串行处理，帧到达顺序即处理顺序，零竞态零乱序）：
 *       <ul>
 *         <li>{"type":"task_log","deviceId","taskId","chunk"}——后台任务实时增量日志，
 *             追加写入该任务的会话日志文件；</li>
 *         <li>{"type":"task_done","deviceId","taskId","exitCode"}——后台任务结束的补报，
 *             向日志文件追加结束标记后摘登记并回投总线。</li>
 *         <li>{"type":"file_chunk","deviceId","taskId","chunk"}——文件回传的数据帧（base64），
 *             解码后顺序写入该下载任务的本机文件；</li>
 *         <li>{"type":"file_done","deviceId","taskId","size"}——文件回传完成，核对字节数后收尾；</li>
 *         <li>{"type":"file_error","deviceId","taskId","error"}——文件回传失败（含被取消），
 *             删除本机半成品并唤醒等待方。</li>
 *       </ul>
 *       卫星端保证 exec 回执帧先于同一任务的任何 task_log / task_done 帧发出，因此回执帧
 *       在本线程完成日志文件初始化与登记之后，随后到达的增量与收尾帧按 peek(taskId) 顺序
 *       追加即可，不存在乱序或查不到任务的缝隙。</li>
 * </ul>
 * <p>
 * 连接断开时，该连接上的所有在途命令立即以异常完成，不再空等命令超时。
 */
@Slf4j
@Component
public class SatelliteBridgeService extends TextWebSocketHandler implements BridgeTaskChannel {

    /** cancel_task 送达等待上限（秒）：取消是轻量命令，不该占用完整 commandTimeout */
    private static final int CANCEL_TIMEOUT_S = 10;

    /** doSend 的超时语义：0 表示不限时（文件传输用，只在断线或出错时失败） */
    private static final int NO_TIMEOUT = 0;

    private final ObjectMapper objectMapper;
    private final CommandTaskRegistry commandTaskRegistry;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final SessionFileManager sessionFileManager;

    /** deviceId → 当前连接 */
    private final Map<String, Connection> devices = new ConcurrentHashMap<>();

    /** requestId → 等待响应的命令 */
    private final Map<String, PendingCommand> pendingRequests = new ConcurrentHashMap<>();

    /** taskId → 正在接收的本机文件落盘任务（文件传输不走后台登记，收完即摘） */
    private final Map<String, DownloadSink> downloads = new ConcurrentHashMap<>();

    /** 命令超时（秒），默认 1800（30 分钟），可在 application.yml 中配置 */
    @Value("${plug.satellite.bridge.command-timeout-s:1800}")
    private int commandTimeout;

    public SatelliteBridgeService(ObjectMapper objectMapper,
                                  CommandTaskRegistry commandTaskRegistry,
                                  BackgroundToolResponseBus backgroundToolResponseBus,
                                  SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.commandTaskRegistry = commandTaskRegistry;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
        this.sessionFileManager = sessionFileManager;
    }

    // ==================== RPC ====================

    /**
     * 遗留调用形态：不指定设备，取当前第一台在线卫星执行。
     * <p>
     * 仅供尚未改造的 ComfyUI 工具编译与最小运行使用——卫星端已不再支持 generate 等
     * 专用命令，运行必然收到「不支持的命令」错误，属预期。
     */
    public String sendCommand(String method, String paramsJson)
            throws ToolExecutor.ToolExecuteException {
        if (devices.isEmpty()) {
            throw new ToolExecutor.ToolExecuteException("ComfyUI 设备不在线");
        }
        if (devices.size() > 1) {
            log.warn("当前有 {} 台卫星机在线，遗留调用将发给其中第一台", devices.size());
        }
        return doSend(devices.values().iterator().next(), method, paramsJson, commandTimeout);
    }

    /** 向指定卫星机下发命令并阻塞等待回执；设备不在线时抛出带在线设备清单的异常。 */
    public String sendCommand(String deviceId, String method, String paramsJson)
            throws ToolExecutor.ToolExecuteException {
        Connection connection = devices.get(deviceId);
        if (connection == null || !connection.session().isOpen()) {
            throw new ToolExecutor.ToolExecuteException(
                    "卫星机 [" + deviceId + "] 不在线。当前在线设备: " + onlineDevices());
        }
        return doSend(connection, method, paramsJson, commandTimeout);
    }

    private String doSend(Connection connection, String method, String paramsJson, int timeoutSec)
            throws ToolExecutor.ToolExecuteException {
        return doSend(connection, method, paramsJson, timeoutSec, null);
    }

    /**
     * 下发一条命令并阻塞等待回执。exec 命令携带 {@link ExecContext}：回执 finished=false
     * （任务转后台）时在 WebSocket 接收线程串行完成日志文件初始化与后台登记，再唤醒调用方。
     */
    private String doSend(Connection connection, String method, String paramsJson, int timeoutSec,
                          ExecContext execContext) throws ToolExecutor.ToolExecuteException {
        String requestId = UUID.randomUUID().toString();
        String commandJson = String.format(
                "{\"id\":\"%s\",\"method\":\"%s\",\"params\":%s}",
                requestId, method, paramsJson != null ? paramsJson : "{}");

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingCommand(future, connection, execContext));

        try {
            synchronized (connection.session()) {
                connection.session().sendMessage(new TextMessage(commandJson));
            }
            return timeoutSec > 0
                    ? future.get(timeoutSec, TimeUnit.SECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 超时（" + timeoutSec + "s）");
        } catch (ExecutionException e) {
            pendingRequests.remove(requestId);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 失败: " + cause.getMessage());
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 发送失败: " + e.getMessage());
        }
    }

    /**
     * 供命令 / 脚本执行器调用的 exec 专用入口：下发请求并阻塞等待回执。
     * <p>
     * 回执 finished=false 时，{@link #handleTextMessage} 已在接收线程创建好会话日志文件
     * （显式后台带与本机同格式的文件头、超时转后台只写窗口内已产出输出）并完成
     * {@link CommandTaskRegistry#registerBridge} 登记——调用方拿到返回值即可直接给出
     * 与本机完全一致的日志文件引用，随后的 task_log / task_done 也按同一顺序追加收尾。
     *
     * @param deviceId          目标卫星机
     * @param taskId            调用方生成的任务 ID（同时用作登记 ID、事件匹配键与日志文件名后缀）
     * @param sessionId         归属会话（日志文件与登记的落点）
     * @param taskType          登记来源（BRIDGE_COMMAND / BRIDGE_SCRIPT）
     * @param title             展示标题：命令原文或脚本标题
     * @param logRelPath        日志相对会话文件目录的路径（logs/bridge_xxx_{taskId}.log）
     * @param explicitBackground 是否显式后台（决定回执转后台时要不要写文件头）
     * @param execParams        exec 请求参数（taskId / mode / content / waitTimeoutSec 等）
     */
    public ExecResult sendExecCommand(String deviceId,
                                      String taskId,
                                      String sessionId,
                                      CommandTaskRegistry.TaskType taskType,
                                      String title,
                                      String logRelPath,
                                      boolean explicitBackground,
                                      Map<String, Object> execParams) throws ToolExecutor.ToolExecuteException {
        Connection connection = devices.get(deviceId);
        if (connection == null || !connection.session().isOpen()) {
            throw new ToolExecutor.ToolExecuteException(
                    "卫星机 [" + deviceId + "] 不在线。当前在线设备: " + onlineDevices());
        }
        String paramsJson;
        try {
            paramsJson = objectMapper.writeValueAsString(execParams);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("exec 参数序列化失败: " + e.getMessage());
        }
        ExecContext ctx = new ExecContext(sessionId, taskType, title, logRelPath, deviceId, explicitBackground);
        String resultJson = doSend(connection, "exec", paramsJson, commandTimeout, ctx);
        return parseExecResult(resultJson);
    }

    /** 在线设备清单文案：deviceId（deviceName）顿号连接；无设备时返回「无」。 */
    public String onlineDevices() {
        if (devices.isEmpty()) {
            return "无";
        }
        return devices.values().stream()
                .map(c -> c.deviceId()
                        + (StringUtils.hasText(c.deviceName()) ? "（" + c.deviceName() + "）" : ""))
                .collect(Collectors.joining("、"));
    }

    /** 生成桥接任务 ID（12 位随机串，同时用作登记表 ID、task_done 事件匹配键与卫星机日志名后缀）。 */
    public static String newTaskId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 解析 exec 回执。回执为 {"error":...} 形态（卫星端 dispatch 失败）时转抛执行异常。
     */
    public ExecResult parseExecResult(String resultJson) throws ToolExecutor.ToolExecuteException {
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            if (node == null || node.isNull()) {
                throw new ToolExecutor.ToolExecuteException("卫星机回执为空");
            }
            if (node.hasNonNull("error")) {
                throw new ToolExecutor.ToolExecuteException("卫星机执行失败: " + node.get("error").asText());
            }
            return objectMapper.treeToValue(node, ExecResult.class);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("解析卫星机回执失败: " + e.getMessage());
        }
    }

    // ==================== 文件下载 ====================

    /**
     * 从卫星机把单个文件经桥接通道拉回本机，阻塞到传输完成。
     * <p>
     * 不设传输超时：只会在卫星机拒绝、传输出错或连接断开时失败。本机文件在命令发出前
     * 就打开并登记进 {@link #downloads}，卫星端保证回执帧先于任何 file_chunk 帧，
     * 因此接收线程按 taskId 必然命中，不存在丢帧的缝隙。
     * </p>
     *
     * @param deviceId   目标卫星机
     * @param remotePath 卫星机上的文件绝对路径
     * @param savePath   本机保存路径（父目录须已存在）
     */
    public DownloadResult download(String deviceId, String remotePath, Path savePath)
            throws ToolExecutor.ToolExecuteException {
        Connection connection = devices.get(deviceId);
        if (connection == null || !connection.session().isOpen()) {
            throw new ToolExecutor.ToolExecuteException(
                    "卫星机 [" + deviceId + "] 不在线。当前在线设备: " + onlineDevices());
        }

        String taskId = newTaskId();
        FileChannel channel;
        try {
            channel = FileChannel.open(savePath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建本机目标文件: " + e.getMessage());
        }

        DownloadSink sink = new DownloadSink(taskId, connection, savePath, channel);
        downloads.put(taskId, sink);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", taskId);
        params.put("remotePath", remotePath);

        long startNanos = System.nanoTime();
        try {
            String paramsJson = objectMapper.writeValueAsString(params);
            String resultJson = doSend(connection, "download", paramsJson, NO_TIMEOUT, null);

            JsonNode meta = objectMapper.readTree(resultJson);
            if (meta == null || meta.isNull()) {
                throw new ToolExecutor.ToolExecuteException("卫星机回执为空");
            }
            if (meta.hasNonNull("error")) {
                throw new ToolExecutor.ToolExecuteException("卫星机拒绝下载: " + meta.get("error").asText());
            }
            sink.expectedSize = meta.path("totalSize").asLong(-1);
            sink.fileName = meta.path("fileName").asText(null);

            // 之后由接收线程写文件，这里只等收尾信号
            sink.done.get();
        } catch (ToolExecutor.ToolExecuteException e) {
            finishDownload(sink, e);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            finishDownload(sink, cause);
            throw new ToolExecutor.ToolExecuteException("文件传输失败: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishDownload(sink, e);
            throw new ToolExecutor.ToolExecuteException("文件传输被中断");
        } catch (Exception e) {
            finishDownload(sink, e);
            throw new ToolExecutor.ToolExecuteException("文件下载失败: " + e.getMessage());
        }

        long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new DownloadResult(deviceId, remotePath, sink.fileName, savePath, sink.received, costMs);
    }

    /** 收尾一个下载任务（幂等）：摘登记、关通道、失败时删掉本机半成品、唤醒等待方 */
    private void finishDownload(DownloadSink sink, Throwable error) {
        downloads.remove(sink.taskId, sink);
        sink.finish(error);
    }

    /** file_chunk 事件：一块 base64 数据，解码后顺序写入本机文件（接收线程单线程写，天然有序） */
    private void handleFileChunk(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        DownloadSink sink = taskId == null ? null : downloads.get(taskId);
        if (sink == null) {
            return;
        }
        String data = (String) msg.get("chunk");
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            sink.write(Base64.getDecoder().decode(data));
        } catch (Exception e) {
            log.warn("文件分块写入失败: taskId={}, error={}", taskId, e.getMessage());
            finishDownload(sink, new ToolExecutor.ToolExecuteException("写入本机文件失败: " + e.getMessage()));
        }
    }

    /** file_done 事件：核对字节数后收尾，唤醒等待中的工具线程 */
    private void handleFileDone(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        DownloadSink sink = taskId == null ? null : downloads.get(taskId);
        if (sink == null) {
            return;
        }
        long size = msg.get("size") instanceof Number n ? n.longValue() : sink.received;
        try {
            sink.channel.force(false);
        } catch (IOException ignored) {
        }
        if (sink.expectedSize >= 0 && size != sink.expectedSize) {
            finishDownload(sink, new ToolExecutor.ToolExecuteException(
                    "文件大小不一致：登记 " + sink.expectedSize + " 字节，实际收到 " + size + " 字节"));
            return;
        }
        finishDownload(sink, null);
        log.info("卫星机文件接收完成: deviceId={}, taskId={}, size={}B", msg.get("deviceId"), taskId, size);
    }

    /** file_error 事件：卫星机回报失败（含被取消），删掉本机半成品并唤醒等待方 */
    private void handleFileError(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        DownloadSink sink = taskId == null ? null : downloads.get(taskId);
        if (sink == null) {
            return;
        }
        String error = (String) msg.get("error");
        finishDownload(sink, new ToolExecutor.ToolExecuteException(
                error != null ? error : "卫星机回报文件传输失败"));
    }

    // ==================== cancel_task 下发（BridgeTaskChannel） ====================

    @Override
    public boolean dispatchCancel(String deviceId, String taskId) {
        try {
            Connection connection = devices.get(deviceId);
            if (connection == null || !connection.session().isOpen()) {
                log.warn("下发 cancel_task 失败，设备不在线: deviceId={}, taskId={}", deviceId, taskId);
                return false;
            }
            String paramsJson = objectMapper.writeValueAsString(Map.of("taskId", taskId));
            String resultJson = doSend(connection, "cancel_task", paramsJson, CANCEL_TIMEOUT_S);
            JsonNode node = objectMapper.readTree(resultJson);
            return node != null && node.path("killed").asBoolean(false);
        } catch (Exception e) {
            log.warn("下发 cancel_task 失败: deviceId={}, taskId={}, error={}", deviceId, taskId, e.getMessage());
            return false;
        }
    }

    // ==================== WebSocket 事件 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("卫星机连接中: {} (session={})", session.getRemoteAddress(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);

            String type = (String) msg.get("type");
            if ("register".equals(type)) {
                handleRegister(session, msg);
                return;
            }
            if ("task_log".equals(type)) {
                handleTaskLog(msg);
                return;
            }
            if ("task_done".equals(type)) {
                handleTaskDone(msg);
                return;
            }
            if ("file_chunk".equals(type)) {
                handleFileChunk(msg);
                return;
            }
            if ("file_done".equals(type)) {
                handleFileDone(msg);
                return;
            }
            if ("file_error".equals(type)) {
                handleFileError(msg);
                return;
            }

            String id = (String) msg.get("id");
            if (id != null) {
                PendingCommand pending = pendingRequests.remove(id);
                if (pending != null) {
                    String result = (String) msg.get("result");
                    String error = (String) msg.get("error");
                    if (error != null) {
                        pending.future().complete("{\"error\":\"" + error + "\"}");
                    } else {
                        // exec 回执在当前接收线程串行处理：finished=false 时先把日志文件建好
                        // （显式后台写文件头、超时转后台只写窗口内输出）并完成后台登记，
                        // 再唤醒调用方——随后到达的 task_log / task_done 按同一顺序追加，零竞态
                        if (pending.execContext() != null) {
                            initBridgeBackgroundLog(pending.execContext(), result);
                        }
                        pending.future().complete(result != null ? result : "{}");
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析卫星机消息失败: {}", e.getMessage());
        }
    }

    /** 注册：按 deviceId 存连接；同 deviceId 重连顶替旧连接并关闭旧 session。 */
    private void handleRegister(WebSocketSession session, Map<String, Object> msg) {
        String deviceId = (String) msg.get("deviceId");
        String deviceName = (String) msg.get("deviceName");
        if (!StringUtils.hasText(deviceId)) {
            log.warn("注册消息缺少 deviceId，忽略: session={}", session.getId());
            return;
        }
        Connection connection = new Connection(deviceId, deviceName, session);
        Connection old = devices.put(deviceId, connection);
        log.info("卫星机已注册: {} ({})", deviceId, deviceName);
        if (old != null && old.session() != session) {
            // 先顶替再关旧连接：旧 session 的收尾不会误删新连接，其上的在途命令由收尾逻辑统一失败
            try {
                old.session().close();
            } catch (IOException ignored) {
            }
            log.info("设备 {} 重复注册，已关闭旧连接", deviceId);
        }
    }

    /**
     * exec 回执 finished=false（任务转后台）时在 WebSocket 接收线程同步完成的初始化：
     * 创建会话日志文件并登记后台任务，再唤醒调用方。
     * <ul>
     *   <li>显式后台：先写与本机显式后台同格式的日志文件头，再写入窗口内已产出的输出；</li>
     *   <li>超时转后台：不写文件头（本机前台超时转后台同样只有输出），直接写入窗口内已产出输出；</li>
     *   <li>初始化完成后登记 {@link CommandTaskRegistry#registerBridge}——随后到达的 task_log /
     *       task_done 帧按 peek(taskId) 命中登记顺序追加，帧序即写入顺序。</li>
     * </ul>
     */
    private void initBridgeBackgroundLog(ExecContext ctx, String resultJson) {
        try {
            ExecResult execResult = parseExecResult(resultJson);
            if (Boolean.TRUE.equals(execResult.getFinished())) {
                return;
            }
            Path logFile = sessionFileManager.prepareSessionFile(ctx.sessionId(), ctx.logRelPath());
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (ctx.explicitBackground()) {
                    String noun = ctx.taskType() == CommandTaskRegistry.TaskType.BRIDGE_SCRIPT ? "脚本" : "命令";
                    writer.write("=== 后台" + noun + "执行日志 ===");
                    writer.newLine();
                    writer.write("启动时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    writer.newLine();
                    writer.write("设备: " + ctx.deviceId() + " (卫星机)");
                    writer.newLine();
                    writer.write(noun + ": " + ctx.title());
                    writer.newLine();
                    writer.write("日志文件: " + SessionFileManager.buildSessionMarker(ctx.logRelPath()));
                    writer.newLine();
                    writer.write("=".repeat(60));
                    writer.newLine();
                    writer.newLine();
                }
                if (StringUtils.hasText(execResult.getOutput())) {
                    writer.write(execResult.getOutput());
                }
                writer.flush();
            }
            commandTaskRegistry.registerBridge(ctx.sessionId(), ctx.taskType(), ctx.title(),
                    ctx.logRelPath(), ctx.deviceId(), execResult.getTaskId());
        } catch (Exception e) {
            log.error("初始化卫星后台任务日志失败: deviceId={}, sessionId={}, logRelPath={}, error={}",
                    ctx.deviceId(), ctx.sessionId(), ctx.logRelPath(), e.getMessage());
        }
    }

    /**
     * task_log 事件：卫星机后台任务的实时增量日志，追加写入该任务的会话日志文件。
     * <p>
     * 与回执帧、task_done 同在 WebSocket 接收线程串行处理，帧到达顺序即文件写入顺序；
     * 登记已在前一帧完成，这里按 peek(taskId) 必然命中。写失败只告警，不影响任务生命周期。
     */
    private void handleTaskLog(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        String chunk = (String) msg.get("chunk");
        if (!StringUtils.hasText(taskId) || chunk == null || chunk.isEmpty()) {
            return;
        }
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.peek(taskId);
        if (task == null) {
            return;
        }
        try {
            Path logFile = sessionFileManager.resolveSessionFilePath(task.getSessionId(), task.getLogPath());
            Files.write(logFile, chunk.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("task_log 追加写入失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * task_done 事件：卫星机后台任务结束 → 日志补写结束标记 → 摘登记 + 回投对话（不查 pendingRequests）。
     * <p>
     * 输出已通过 task_log 全部实时落盘，这里至多再补一段残余 output；收尾三件套
     * （追加 Footer、摘登记、回投文案）与本机后台任务完全同一套措辞。
     */
    private void handleTaskDone(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        if (!StringUtils.hasText(taskId)) {
            log.warn("task_done 事件缺少 taskId，忽略");
            return;
        }
        String deviceId = (String) msg.get("deviceId");
        Object exitCode = msg.get("exitCode");
        String output = (String) msg.get("output");

        CommandTaskRegistry.RunningTask task = commandTaskRegistry.peek(taskId);
        if (task == null) {
            log.info("收到 task_done 但无对应登记（任务可能已结束或从未登记）: deviceId={}, taskId={}",
                    deviceId, taskId);
            return;
        }
        CommandTaskRegistry.TaskType type = task.getType();
        if (type != CommandTaskRegistry.TaskType.BRIDGE_COMMAND
                && type != CommandTaskRegistry.TaskType.BRIDGE_SCRIPT) {
            log.warn("task_done 对应的登记不是桥接任务，忽略: taskId={}, type={}", taskId, type);
            return;
        }

        boolean script = type == CommandTaskRegistry.TaskType.BRIDGE_SCRIPT;
        boolean killed = task.getKilled().get();
        String noun = script ? "脚本" : "命令";
        String toolName = script ? ExtensionScriptTool.NAME : CommandTool.NAME;
        String footerMsg;
        if (killed) {
            footerMsg = noun + "已被用户手动结束";
        } else if (exitCode == null) {
            footerMsg = noun + "执行异常，退出码未知";
        } else {
            footerMsg = noun + "执行完成，退出码: " + exitCode;
        }

        // 本机同款规矩：日志实时落盘已完成，这里只补残余输出与统一格式的结束标记
        try {
            Path logFile = sessionFileManager.resolveSessionFilePath(task.getSessionId(), task.getLogPath());
            if (StringUtils.hasText(output)) {
                Files.write(logFile, output.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            OSToolKit.appendFooter(logFile, footerMsg);
        } catch (IOException e) {
            log.warn("task_done 日志收尾写入失败: taskId={}, error={}", taskId, e.getMessage());
        }

        StringBuilder text = new StringBuilder(footerMsg);
        text.append("\n").append(noun).append(": ").append(task.getTitle());
        text.append("\n日志文件: ").append(SessionFileManager.buildSessionMarker(task.getLogPath()));

        commandTaskRegistry.finish(taskId);
        backgroundToolResponseBus.post(task.getSessionId(), toolName, text.toString());
        log.info("卫星机任务完成回投: deviceId={}, taskId={}, exitCode={}", deviceId, taskId, exitCode);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 按 session 定位设备条目；register 顶替场景下旧 session 已不在表中，不会误删新连接
        String deviceId = null;
        for (Iterator<Map.Entry<String, Connection>> it = devices.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Connection> entry = it.next();
            if (entry.getValue().session() == session) {
                deviceId = entry.getKey();
                it.remove();
            }
        }

        // 该连接上的所有在途命令立即失败，不再空等命令超时（修旧实现断线挂满 commandTimeout 的债）
        int failed = 0;
        for (Iterator<Map.Entry<String, PendingCommand>> it = pendingRequests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PendingCommand> entry = it.next();
            if (entry.getValue().connection().session() == session) {
                entry.getValue().future().completeExceptionally(new ToolExecutor.ToolExecuteException(
                        (deviceId != null ? "卫星机 [" + deviceId + "] 连接已断开" : "卫星机连接已断开")
                                + "，命令未完成"));
                it.remove();
                failed++;
            }
        }
        // 该连接上未传完的文件同样立即失败，并删掉本机半成品
        int failedDownloads = 0;
        for (Iterator<Map.Entry<String, DownloadSink>> it = downloads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, DownloadSink> entry = it.next();
            if (entry.getValue().connection.session() == session) {
                it.remove();
                entry.getValue().finish(new ToolExecutor.ToolExecuteException(
                        (deviceId != null ? "卫星机 [" + deviceId + "] 连接已断开" : "卫星机连接已断开")
                                + "，文件传输未完成"));
                failedDownloads++;
            }
        }
        log.info("卫星机已断开: deviceId={}, 在途命令失败 {} 条, 未传完文件 {} 个, status={}",
                deviceId, failed, failedDownloads, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("卫星机连接传输错误 [{}]: {}", session.getId(), exception.getMessage());
        // 移除该 session 对应的连接；在途命令由随后的 afterConnectionClosed 统一失败
        devices.entrySet().removeIf(entry -> entry.getValue().session() == session);
    }

    // ==================== 数据结构 ====================

    /** 一台在线卫星机的当前连接 */
    private record Connection(String deviceId, String deviceName, WebSocketSession session) {
    }

    /** 一次文件下载的结果 */
    public record DownloadResult(String deviceId, String remotePath, String fileName,
                                 Path savePath, long size, long costMs) {
    }

    /**
     * 一个正在接收的本机落盘任务。写入与关闭都只发生在 WebSocket 接收线程，
     * 工具线程只等待 {@link #done}，因此不需要额外同步。
     */
    private static final class DownloadSink {

        private final String taskId;
        private final Connection connection;
        private final Path file;
        private final FileChannel channel;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 卫星端回执里登记的总字节数（-1 表示未知） */
        private long expectedSize = -1;
        /** 卫星端回执里的文件名（仅用于展示） */
        private String fileName;
        /** 已落盘字节数 */
        private long received;

        private DownloadSink(String taskId, Connection connection, Path file, FileChannel channel) {
            this.taskId = taskId;
            this.connection = connection;
            this.file = file;
            this.channel = channel;
        }

        /** 追加一块数据（接收线程唯一写者） */
        private void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            received += bytes.length;
        }

        /** 收尾（幂等）：关通道、失败时删掉半成品、唤醒等待方 */
        private void finish(Throwable error) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            if (error != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                done.completeExceptionally(error);
            } else {
                done.complete(null);
            }
        }
    }

    /** 一条在途命令：等待回执的 future + 所属连接（连接断开时按连接成批失败）+ exec 命令的收尾上下文（非 exec 命令为 null） */
    private record PendingCommand(CompletableFuture<String> future, Connection connection, ExecContext execContext) {
    }

    /**
     * 一条在途 exec 命令的收尾上下文：回执 finished=false 时在 WebSocket 接收线程
     * 按它创建会话日志文件、写文件头并登记后台任务。
     */
    private record ExecContext(String sessionId, CommandTaskRegistry.TaskType taskType, String title,
                               String logRelPath, String deviceId, boolean explicitBackground) {
    }
}
