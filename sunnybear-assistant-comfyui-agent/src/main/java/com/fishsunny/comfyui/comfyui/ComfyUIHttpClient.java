package com.fishsunny.comfyui.comfyui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.comfyui.dto.*;
import lombok.Getter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * ComfyUI REST API 客户端。只暴露子 Agent 和主工具需要的三个操作：
 * {@link #generate}, {@link #getResources}, {@link #viewImage}。
 */
public class ComfyUIHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIHttpClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final Path workflowDir;
    /**
     * -- GETTER --
     * 默认生图超时（秒）
     */
    @Getter
    private final int defaultGenerateTimeout;

    public ComfyUIHttpClient(String baseUrl, String workflowPath, int defaultGenerateTimeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.workflowDir = resolvePath(workflowPath);
        this.defaultGenerateTimeout = defaultGenerateTimeout > 0 ? defaultGenerateTimeout : 1800;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "comfyui-poll");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== 公开 API ====================

    /** 提交 workflow，轮询等待完成，返回结果（含 outputs 图片信息） */
    public HistoryEntry generate(String workflowJson, int timeoutSec) throws Exception {
        long t0 = System.currentTimeMillis();

        // 1. 提交
        Map<String, Object> workflow = objectMapper.readValue(workflowJson,
                new TypeReference<Map<String, Object>>() {});
        PromptResponse pr = post(Map.of("prompt", workflow), PromptResponse.class);

        String promptId = pr.getPromptId();
        if (promptId == null || promptId.isEmpty()) {
            throw new IOException("提交 workflow 失败，未返回 prompt_id");
        }
        long t1 = System.currentTimeMillis();
        log.info("Workflow 已提交，prompt_id={}, 耗时={}ms", promptId, t1 - t0);

        // 2. 用 CompletableFuture 轮询
        int effectiveTimeout = timeoutSec > 0 ? timeoutSec : defaultGenerateTimeout;
        waitForCompletion(promptId, effectiveTimeout);
        long t2 = System.currentTimeMillis();
        log.info("轮询完成，prompt_id={}, 等待耗时={}ms", promptId, t2 - t1);

        // 3. 取结果
        HistoryEntry entry = fetchHistory(promptId);
        long t3 = System.currentTimeMillis();
        log.info("历史记录已获取，prompt_id={}, 获取耗时={}ms, 总耗时={}ms", promptId, t3 - t2, t3 - t0);

        return entry;
    }

    /** 获取可用资源：全面获取所有模型 / LoRA / VAE / 采样器 / 调度器等 */
    public ResourceInfo getResources() throws Exception {
        Map<String, NodeInfo> nodes = get("/object_info",
                new TypeReference<Map<String, NodeInfo>>() {});
        return ResourceInfo.from(nodes);
    }

    /** 通过文件名获取图片 Base64 */
    public ViewImageResult viewImage(String filename, String type) throws Exception {
        String t = (type != null && !type.isEmpty()) ? type : "output";
        String url = baseUrl + "/view?filename=" + filename + "&type=" + t;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取图片失败，HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("响应体为空");

            byte[] bytes = body.bytes();
            return new ViewImageResult()
                    .setFilename(filename)
                    .setType(t)
                    .setContentType(response.header("Content-Type", "image/png"))
                    .setSize(bytes.length)
                    .setBase64(Base64.getEncoder().encodeToString(bytes));
        }
    }

    // ==================== internal ====================

    /**
     * 获取历史记录，带重试（ComfyUI 队列清空后可能短暂还没写入 history）。
     * 最多重试 5 次，每次间隔 2s。
     */
    private HistoryEntry fetchHistory(String promptId) throws Exception {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            String json = rawGet("/history/" + promptId);
            Map<String, HistoryEntry> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, HistoryEntry>>() {});
            HistoryEntry entry = map.get(promptId);
            if (entry != null) return entry;

            if (i < maxRetries - 1) {
                log.info("history 尚未就绪，{}ms 后重试 ({}/{}): prompt_id={}",
                        2000, i + 1, maxRetries, promptId);
                Thread.sleep(2000);
            }
        }
        throw new IOException("未找到结果（已重试 " + maxRetries + " 次），prompt_id=" + promptId);
    }

    /** 每 2s 查一次队列，直到 promptId 不在队列中或超时 */
    private void waitForCompletion(String promptId, int timeoutSec) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        int[] pollCount = {0};
        long pollStart = System.currentTimeMillis();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                pollCount[0]++;
                QueueStatus qs = get("/queue", QueueStatus.class);
                int running = qs.getQueueRunning() != null ? qs.getQueueRunning().size() : 0;
                int pending = qs.getQueuePending() != null ? qs.getQueuePending().size() : 0;

                if (!qs.containsPrompt(promptId)) {
                    long elapsed = System.currentTimeMillis() - pollStart;
                    log.info("队列轮询完成: prompt_id={}, 轮询次数={}, 耗时={}ms, 当前队列: running={}, pending={}",
                            promptId, pollCount[0], elapsed, running, pending);
                    future.complete(null);
                } else if (pollCount[0] % 5 == 1) {
                    // 每 5 次（约 10s）输出一次进度
                    long elapsed = System.currentTimeMillis() - pollStart;
                    log.info("队列轮询中: prompt_id={}, 轮询次数={}, 已等待={}s, 队列: running={}, pending={}",
                            promptId, pollCount[0], elapsed / 1000, running, pending);
                }
            } catch (Exception e) {
                log.error("队列轮询异常: prompt_id={}, error={}", promptId, e.getMessage());
                future.completeExceptionally(e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("生成超时: prompt_id={}, 超时={}s, 总轮询次数={}", promptId, timeoutSec, pollCount[0]);
            throw new IOException("生成超时（" + timeoutSec + "s），prompt_id=" + promptId);
        } finally {
            task.cancel(true);
        }
    }

    // ---- workflow file helpers ----

    /** 列出 workflowDir 下所有 .json 工作流文件 */
    public Map<String, Object> listWorkflows() throws Exception {
        List<Map<String, Object>> workflows = new ArrayList<>();

        if (!Files.exists(workflowDir)) {
            Files.createDirectories(workflowDir);
        }

        try (Stream<Path> stream = Files.list(workflowDir)) {
            List<Path> jsonFiles = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();

            for (Path file : jsonFiles) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", file.getFileName().toString());
                info.put("size", Files.size(file));
                info.put("lastModified", Files.getLastModifiedTime(file).toString());

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = objectMapper.readValue(file.toFile(), Map.class);
                    info.put("nodeCount", json.size());

                    List<String> nodeTypes = new ArrayList<>();
                    for (Object value : json.values()) {
                        if (value instanceof Map<?, ?> node) {
                            Object classType = node.get("class_type");
                            if (classType != null) {
                                nodeTypes.add(classType.toString());
                            }
                        }
                    }
                    info.put("nodeTypes", nodeTypes);
                } catch (Exception e) {
                    log.warn("解析工作流文件失败 [{}]: {}", file.getFileName(), e.getMessage());
                    info.put("parseError", e.getMessage());
                }

                workflows.add(info);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", workflowDir.toAbsolutePath().toString());
        result.put("count", workflows.size());
        result.put("workflows", workflows);

        return result;
    }

    /** 读取指定工作流文件的完整 JSON */
    public Map<String, Object> getWorkflowDetail(String workflowName) throws Exception {
        if (workflowName == null || workflowName.isEmpty()) {
            throw new IOException("参数 workflowName 不能为空");
        }

        Path file = workflowDir.resolve(workflowName);

        // 安全检查：防止路径穿越
        if (!file.normalize().startsWith(workflowDir.normalize())) {
            throw new IOException("非法的文件名: " + workflowName);
        }

        if (!Files.exists(file)) {
            throw new IOException("工作流文件不存在: " + file.toAbsolutePath());
        }

        String content = Files.readString(file);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", workflowName);
        result.put("path", file.toAbsolutePath().toString());
        result.put("size", Files.size(file));
        result.put("workflow", objectMapper.readValue(content, Map.class));

        return result;
    }

    private static Path resolvePath(String pathStr) {
        Path p = Paths.get(pathStr);
        if (!p.isAbsolute()) {
            p = Paths.get("").toAbsolutePath().resolve(p).normalize();
        }
        return p;
    }

    // ---- HTTP helpers ----

    private String rawGet(String path) throws Exception {
        Request r = new Request.Builder().url(baseUrl + path).build();
        try (Response resp = httpClient.newCall(r).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " " + path);
            ResponseBody body = resp.body();
            return body != null ? body.string() : "{}";
        }
    }

    private <T> T get(String path, Class<T> clazz) throws Exception {
        return objectMapper.readValue(rawGet(path), clazz);
    }

    private <T> T get(String path, TypeReference<T> typeRef) throws Exception {
        return objectMapper.readValue(rawGet(path), typeRef);
    }

    private <T> T post(Object requestBody, Class<T> clazz) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request r = new Request.Builder().url(baseUrl + "/prompt").post(body).build();
        try (Response resp = httpClient.newCall(r).execute()) {
            if (!resp.isSuccessful()) {
                String err = resp.body() != null ? resp.body().string() : "";
                throw new IOException("HTTP " + resp.code() + " " + "/prompt" + ": " + err);
            }
            String rb = resp.body() != null ? resp.body().string() : "{}";
            return objectMapper.readValue(rb, clazz);
        }
    }
}
