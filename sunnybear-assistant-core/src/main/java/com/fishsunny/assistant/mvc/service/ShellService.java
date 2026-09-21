package com.fishsunny.assistant.mvc.service;

/*
 * @Usage Shell 命令执行服务 —— 供前端「终端」面板使用。
 *
 *        与 AI 侧的 CommandTool 是两套东西，不是重复造轮子：
 *        · CommandTool：给模型用，带白名单/黑名单/AI 危险审查/用户确认；
 *        · 本服务：人自己动手敲命令，不做审查（本来就是人自己的意图）。
 *        两者唯一的共同点是「都往平台 shell 里丢一条命令」，没有值得抽的公共逻辑。
 *
 *        设计要点：
 *        · 异步：start 立即返回 job，输出由调用方按 offset 增量拉取。
 *          命令可以跑任意久，不受 HTTP 请求超时约束。
 *        · 可中断：kill 先杀子孙进程再杀本体 —— cmd.exe /c 会带出一串子进程，
 *          只杀父进程会留下孤儿进程继续跑。
 *        · 输出上限：超出上限后丢弃并标记 truncated，保证读线程不会把内存吃光，
 *          同时继续把管道读干净（不读会把进程自己卡死）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/21
 */

import com.fishsunny.assistant.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ShellService {

    /** 单个任务保留的输出字符上限，超出部分丢弃并标记截断 */
    private static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;

    /** 已结束任务的保留时长：超时后从内存里清掉（前端拉过输出就不需要了） */
    private static final long JOB_KEEP_MILLIS = 30 * 60 * 1000L;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Map<String, ShellJob> jobs = new ConcurrentHashMap<>();

    /** 平台信息：默认工作目录、系统名、shell 名（前端展示用） */
    public ShellInfo info() {
        return new ShellInfo(
                System.getProperty("user.dir"),
                IS_WINDOWS ? "Windows" : "Linux",
                IS_WINDOWS ? "cmd.exe" : "bash"
        );
    }

    /**
     * 启动一条命令，立即返回任务句柄。
     *
     * @param cwd 工作目录；为空时用进程启动目录（项目根）
     */
    public ShellJob start(String command, String cwd) throws IOException {
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException("命令不能为空");
        }
        Path workDir = resolveCwd(cwd);
        String[] shell = IS_WINDOWS
                ? new String[]{"cmd.exe", "/c"}
                : new String[]{"bash", "-c"};

        ProcessBuilder processBuilder = new ProcessBuilder(shell[0], shell[1], command);
        processBuilder.directory(workDir.toFile());
        // 合并 stderr：终端里本来就该看到一起，不分开
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        ShellJob job = new ShellJob(UUID.randomUUID().toString(), command, workDir.toString(), process);
        jobs.put(job.id, job);

        // 虚拟线程持续读管道：不读的话缓冲写满会把进程卡死
        Thread.ofVirtual().name("shell-pump-" + job.id).start(() -> pump(job));

        cleanupExpired();
        log.info("shell 任务已启动: id={}, cwd={}, command={}", job.id, workDir, command);
        return job;
    }

    public ShellJob get(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        ShellJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在或已过期: " + jobId);
        }
        return job;
    }

    /**
     * 强杀任务。
     *
     * @return true 表示本次确实杀了一个还在跑的任务；false 表示它早就结束了
     */
    public boolean kill(String jobId) {
        ShellJob job = get(jobId);
        if (!job.running) {
            return false;
        }
        // 连子孙一起收：cmd/bash 派生的子进程，只 destroy 父进程是收不掉的
        ProcessUtils.killTree(job.process);
        log.info("shell 任务已终止: id={}, command={}", job.id, job.command);
        return true;
    }

    /* ======================== 内部实现 ======================== */

    /** 读管道直到 EOF，然后把退出码写回任务 */
    private void pump(ShellJob job) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(job.process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                job.append(new String(buffer, 0, read));
            }
        } catch (IOException e) {
            log.warn("读取命令输出失败: id={}, command={}", job.id, job.command, e);
        } finally {
            try {
                job.exitCode = job.process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            job.running = false;
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private Path resolveCwd(String cwd) {
        if (!StringUtils.hasText(cwd)) {
            return Path.of(System.getProperty("user.dir"));
        }
        Path path = Path.of(cwd.trim());
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("工作目录不存在: " + cwd);
        }
        return path;
    }

    /** 清掉早就结束、前端也不会再问的任务，避免内存里越积越多 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(entry -> {
            ShellJob job = entry.getValue();
            return !job.running && job.finishedAt > 0 && now - job.finishedAt > JOB_KEEP_MILLIS;
        });
    }

    /* ======================== 数据结构 ======================== */

    /** 平台信息 */
    public record ShellInfo(String defaultCwd, String os, String shell) {
    }

    /** 一次命令执行任务 */
    public static class ShellJob {

        public final String id;
        public final String command;
        public final String cwd;
        public final Process process;
        public final long startedAt = System.currentTimeMillis();

        private final StringBuilder output = new StringBuilder();

        /** 是否因为超过上限而丢过输出 */
        public volatile boolean truncated = false;
        public volatile boolean running = true;
        public volatile Integer exitCode = null;
        public volatile long finishedAt = 0L;

        ShellJob(String id, String command, String cwd, Process process) {
            this.id = id;
            this.command = command;
            this.cwd = cwd;
            this.process = process;
        }

        /** 追加输出；超过上限就丢（但读线程继续读，别把进程堵死） */
        synchronized void append(String text) {
            int room = MAX_OUTPUT_CHARS - output.length();
            if (room <= 0) {
                truncated = true;
                return;
            }
            if (text.length() > room) {
                output.append(text, 0, room);
                truncated = true;
            } else {
                output.append(text);
            }
        }

        /** 取 offset 之后的输出片段 */
        public synchronized String readFrom(long offset) {
            if (offset < 0) {
                offset = 0;
            }
            if (offset >= output.length()) {
                return "";
            }
            return output.substring((int) offset);
        }

        /** 当前输出总长度，作为下一次拉取的 offset */
        public synchronized long outputLength() {
            return output.length();
        }
    }
}
