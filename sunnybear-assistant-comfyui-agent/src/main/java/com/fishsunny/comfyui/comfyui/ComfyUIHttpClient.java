package com.fishsunny.comfyui.comfyui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.comfyui.dto.*;
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

    public ComfyUIHttpClient(String baseUrl, String workflowPath) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.workflowDir = resolvePath(workflowPath);
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
        // 1. 提交
        Map<String, Object> workflow = objectMapper.readValue(workflowJson,
                new TypeReference<Map<String, Object>>() {});
        PromptResponse pr = post(Map.of("prompt", workflow), PromptResponse.class);

        String promptId = pr.getPromptId();
        if (promptId == null || promptId.isEmpty()) {
            throw new IOException("提交 workflow 失败，未返回 prompt_id");
        }
        log.info("Workflow 已提交，prompt_id={}", promptId);

        // 2. 用 CompletableFuture 轮询
        int effectiveTimeout = timeoutSec > 0 ? timeoutSec : 120;
        waitForCompletion(promptId, effectiveTimeout);

        // 3. 取结果
        return fetchHistory(promptId);
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

    private HistoryEntry fetchHistory(String promptId) throws Exception {
        String json = rawGet("/history/" + promptId);
        Map<String, HistoryEntry> map = objectMapper.readValue(json,
                new TypeReference<Map<String, HistoryEntry>>() {});
        HistoryEntry entry = map.get(promptId);
        if (entry == null) throw new IOException("未找到结果，prompt_id=" + promptId);
        return entry;
    }

    /** 每 2s 查一次队列，直到 promptId 不在队列中或超时 */
    private void waitForCompletion(String promptId, int timeoutSec) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                QueueStatus qs = get("/queue", QueueStatus.class);
                if (!qs.containsPrompt(promptId)) {
                    future.complete(null);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
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
