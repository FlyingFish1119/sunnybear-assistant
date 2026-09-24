package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP stdio 传输：把 MCP Server 作为子进程拉起来，用它的 stdin/stdout 收发消息。
 *        消息按 MCP stdio 规范以换行分隔（一条消息一行 JSON，消息内部不得含换行）。
 *        子进程 stderr 单独转发到应用日志，避免管道缓冲区写满导致子进程阻塞。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fishsunny.assistant.settings.McpSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class McpStdioClient extends AbstractMcpClient {

    /** 子进程收到终止信号后的等待时间，超时则强杀 */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(3);

    /** 读线程终止哨兵：不是合法 JSON-RPC 响应，仅用于唤醒等待中的主线程 */
    private static final JsonNode POISON = MissingNode.getInstance();

    /** 单行日志截断长度 */
    private static final int LOG_LINE_LIMIT = 500;

    private final LinkedBlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    private Process process;

    private BufferedWriter stdin;

    public McpStdioClient(McpSettings.Client client, ObjectMapper objectMapper) {
        super(client, objectMapper);
    }

    @Override
    protected synchronized void open() {
        if (process != null && process.isAlive()) {
            return;
        }
        closeTransport(); // 清理已死亡进程的残留
        if (!StringUtils.hasText(client.getCommand())) {
            throw new McpException("MCP Server [" + serverName() + "] transport=stdio 但未配置 command");
        }

        List<String> command = new ArrayList<>();
        command.add(client.getCommand());
        if (client.getArgs() != null) {
            command.addAll(client.getArgs());
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (StringUtils.hasText(client.getCwd())) {
            builder.directory(new File(client.getCwd()));
        }
        if (client.getEnv() != null && !client.getEnv().isEmpty()) {
            builder.environment().putAll(client.getEnv());
        }

        try {
            process = builder.start();
        } catch (IOException e) {
            throw new McpException("启动 MCP 子进程失败 [" + String.join(" ", command) + "]: " + e.getMessage(), e);
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        inbox.clear();
        startStdoutReader(new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
        startStderrReader(new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)));
        log.info("已启动 MCP 子进程: {} (pid={})", String.join(" ", command), process.pid());
    }

    @Override
    protected synchronized JsonNode exchange(JsonNode envelope) {
        long id = envelope.path("id").asLong();
        writeLine(envelope);

        long deadline = System.currentTimeMillis() + timeoutMillis();
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                throw new McpException("MCP Server [" + serverName() + "] 等待响应超时（"
                        + client.getTimeoutS() + "s），method=" + envelope.path("method").asText());
            }
            JsonNode node = poll(remain);
            if (node == null) {
                throw new McpException("MCP Server [" + serverName() + "] 等待响应超时（"
                        + client.getTimeoutS() + "s），method=" + envelope.path("method").asText());
            }
            if (node.isMissingNode()) {
                // 子进程已退出：交给骨架重建传输并重试
                throw new McpException.SessionExpiredException();
            }
            JsonNode idNode = node.get("id");
            if (idNode != null && idNode.canConvertToLong() && idNode.asLong() == id) {
                return node;
            }
            // 服务端主动通知或其它消息：记录后忽略
            log.debug("MCP Server [{}] 收到异步消息: {}", serverName(), node);
        }
    }

    @Override
    protected synchronized void send(JsonNode envelope) {
        writeLine(envelope);
    }

    @Override
    protected synchronized void closeTransport() {
        if (process == null) {
            return;
        }
        Process target = process;
        process = null;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 关闭 stdin 只是给子进程一个退出信号，失败不影响后续强杀
        }
        stdin = null;

        target.destroy();
        try {
            if (!target.waitFor(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                target.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
        log.info("已关闭 MCP 子进程: {}", serverName());
    }

    @Override
    protected String transport() {
        return "stdio";
    }

    @Override
    protected void reset() {
        closeTransport(); // 子进程可能已死或卡死，先清干净再重建
        super.reset();
    }

    /** 写入一行 JSON 并 flush；MCP stdio 规范要求消息内不得含换行，Jackson 默认输出紧凑 JSON */
    private void writeLine(JsonNode envelope) {
        if (stdin == null) {
            throw new McpException("MCP Server [" + serverName() + "] 子进程未启动");
        }
        try {
            stdin.write(envelope.toString());
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            throw new McpException("向 MCP 子进程写入失败: " + e.getMessage(), e);
        }
    }

    private JsonNode poll(long remainMillis) {
        try {
            return inbox.poll(remainMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("等待 MCP 子进程响应被中断", e);
        }
    }

    /** stdout 逐行读：每行一条 JSON 消息；读到流结束表示子进程已退出，投递哨兵唤醒等待者 */
    private void startStdoutReader(BufferedReader reader) {
        Thread.ofVirtual().name("mcp-stdout-" + serverName()).start(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!StringUtils.hasText(line)) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if (node != null && node.isObject()) {
                            inbox.offer(node);
                        }
                    } catch (Exception e) {
                        log.warn("MCP Server [{}] 输出非 JSON 行，已忽略: {}", serverName(), truncate(line));
                    }
                }
            } catch (IOException ignored) {
                // 子进程终止时的正常现象
            } finally {
                inbox.offer(POISON);
            }
        });
    }

    /** stderr 单独消费：不读干净会写满管道缓冲区，导致子进程直接卡死 */
    private void startStderrReader(BufferedReader reader) {
        Thread.ofVirtual().name("mcp-stderr-" + serverName()).start(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("MCP Server [{}] stderr: {}", serverName(), line);
                }
            } catch (IOException ignored) {
                // 子进程终止时的正常现象
            }
        });
    }

    private String truncate(String text) {
        return text.length() > LOG_LINE_LIMIT ? text.substring(0, LOG_LINE_LIMIT) + "..." : text;
    }
}
