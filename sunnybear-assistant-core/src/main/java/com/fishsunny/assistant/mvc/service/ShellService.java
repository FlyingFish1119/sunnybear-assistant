package com.fishsunny.assistant.mvc.service;

/*
 * @Usage Shell 命令执行服务 —— 供前端「终端」面板使用。
 *
 *        与 AI 侧的 CommandTool 是两套东西，不是重复造轮子：
 *        · CommandTool：给模型用，带白名单/黑名单/AI 危险审查/用户确认；
 *        · 本服务：人自己动手敲命令，不做审查（本来就是人自己的意图）。
 *
 *        运行模型：一个「常驻 shell 进程」贯穿始终（Linux 用 bash，Windows 用 PowerShell）。
 *        一次性 `bash -c` / `cmd /c` 最大的问题是每条命令各起一个短命进程，
 *        `cd` 换的目录、`export` 设的环境变量，命令一结束全丢——即所谓「残血」。
 *        这里改为起一个常驻 shell，把命令写进它的 stdin、从它的 stdout 读结果，
 *        于是 `cd`、环境变量、工作目录状态都能跨命令保持。
 *
 *        结束判定：shell 自己不会告诉调用方「这条命令跑完了」，所以每条命令尾部
 *        追加一条「结束哨兵」 print（echo），哨兵里带唯一 token 与 $?。泵线程读到哨兵
 *        即切段：token 之前是这条命令的输出，$? 是它的退出码。cd/export 这类没有输出的
 *        命令，也能靠哨兵确认它执行到了。
 *
 *        任务生命周期：任务结束后仍保留在 history 里，供前端把输出拉完（沿用旧的
 *        30 分钟保留期），超期或常驻 shell 重建时清理。
 *
 *        并发模型：全局单 shell —— 同一时刻只接受一条「在跑」的命令。
 *        上一条还没等到哨兵（还在跑/被卡住）时，新一轮 exec 直接拒绝，
 *        而不是排队 —— 排队会让「命令和输出」对不上号，更难排查。
 *        卡住的命令不做自动打断：超时只是没有输出，由用户手动重开会话来解。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/21
 */

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ShellService {

    /** 单个任务保留的输出字符上限，超出部分丢弃并标记截断 */
    private static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;

    /** 已结束任务的保留时长：超时后从历史表里清掉 */
    private static final long JOB_KEEP_MILLIS = 30 * 60 * 1000L;

    /** 哨兵前缀：唯一 token 保证不会和命令自身输出撞车 */
    private static final String DONE_MARK = "__SB_SHELL_DONE__";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 全局唯一的常驻 shell。lazy 启动，进程挂掉后下次 exec 自动重建 */
    private final Object shellLock = new Object();

    private volatile PersistentShell shell;

    /** 任务历史（含正在跑的与已结束的），供前端按 jobId 拉输出 */
    private final Map<String, ShellJob> history = new ConcurrentHashMap<>();

    /** 给每条命令分配递增序号，便于前端观察顺序 */
    private final AtomicLong jobSeq = new AtomicLong(0);

    /** 平台信息：默认工作目录、系统名、shell 名（前端展示用） */
    public ShellInfo info() {
        return new ShellInfo(
                System.getProperty("user.dir"),
                IS_WINDOWS ? "Windows" : "Linux",
                IS_WINDOWS ? "PowerShell" : "bash"
        );
    }

    /**
     * 往常驻 shell 里写一条命令，立即返回任务句柄。
     *
     * @param cwd 起始工作目录；为空时用进程启动目录（项目根）。
     *            注意：仅在「本 shell 首次启动」时生效 —— 之后 shell 的工作目录
     *            由命令里的 cd 决定，这才是持久会话的意义。
     */
    public ShellJob start(String command, String cwd) throws IOException {
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException("命令不能为空");
        }
        PersistentShell sh = ensureShell(cwd);
        synchronized (sh) {
            if (sh.current != null && sh.current.running) {
                throw new IllegalStateException(
                        "上一条命令仍在执行中，请等它结束或点击终止后再执行新命令");
            }
            String jobId = "job-" + jobSeq.incrementAndGet() + "-" + UUID.randomUUID();
            ShellJob job = new ShellJob(jobId, command, sh.cwdSnapshot());
            job.running = true;

            String token = UUID.randomUUID().toString();
            job.token = token;
            sh.current = job;
            history.put(jobId, job);

            // 把「命令 + 哨兵」写进常驻 shell 的 stdin。
            // 哨兵带上唯一 token 与 $?/%errorlevel%，供泵线程切段并取退出码。
            sh.stdin.write(command);
            sh.stdin.newLine();
            sh.stdin.write(sentinelCommand(token));
            sh.stdin.newLine();
            sh.stdin.flush();

            cleanupExpired();
            log.info("shell 命令已下达: id={}, command={}", jobId, command);
            return job;
        }
    }

    /**
     * 生成哨兵命令。
     *
     * <p>哨兵格式：`__SB_SHELL_DONE__ <token> <exitCode> <cwd>`。
     * 除了退出码，还把命令执行完时的当前目录一并带回来 —— 前端据此显示真实的路径栏，
     * 这也是「cd 之后路径栏跟着变」的实现方式（不必额外跑一条 pwd）。
     *
     * <p>bash 用 `$?` 与 `$PWD`；PowerShell 用 `$LASTEXITCODE` 与 `(Get-Location).Path`
     * （纯 cmdlet 未设置 $LASTEXITCODE 时该段为空，后端解析为 null）。
     *
     * <p>之所以把退出码塞进哨兵行、而不是等进程结束由父进程 waitFor：
     * 常驻 shell 不会每条命令就退出一次，拿不到「这条命令」的退出码，
     * 只能在命令自身执行完后立刻抓 `$?` / `$LASTEXITCODE`。
     */
    private String sentinelCommand(String token) {
        String head = DONE_MARK + " " + token;
        if (IS_WINDOWS) {
            // PowerShell：形如 __SB_SHELL_DONE__ <token> 0 C:\path
            return "'" + head + " ' + $LASTEXITCODE + ' ' + (Get-Location).Path";
        }
        // bash：printf 显式补换行；$PWD 为命令执行完时的当前目录
        return "printf '" + head + " %s %s\\n' \"$?\" \"$PWD\"";
    }

    public ShellJob get(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        ShellJob job = history.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在或已过期: " + jobId);
        }
        return job;
    }

    /**
     * 终止「当前正在执行的命令」。
     *
     * <p>档位一不做复杂的中断：这里只把当前任务标记为已结束、让前端不再等它。
     * 注意常驻 shell 本身仍在跑那条命令 —— 若命令真卡死，请调用 closeShell()
     * 重建一个干净的 shell（会连卡住的命令一起收掉）。
     *
     * @return true 表示确实结束了一个还在跑的任务；false 表示它早就结束了
     */
    public boolean kill(String jobId) {
        ShellJob job = get(jobId);
        if (!job.running) {
            return false;
        }
        job.finish(null);
        PersistentShell sh = shell;
        if (sh != null && sh.current == job) {
            sh.current = null; // 断开认领，让泵线程丢弃该命令后续输出
        }
        log.info("shell 任务已标记终止: id={}, command={}", job.id, job.command);
        return true;
    }

    /** 关闭常驻 shell：强杀整棵树并置空，下次 exec 再重建一个干净的 */
    public void closeShell() {
        PersistentShell old;
        synchronized (shellLock) {
            old = shell;
            shell = null;
        }
        if (old != null) {
            old.shutdown();
            log.info("常驻 shell 已关闭");
        }
    }

    /* ======================== 内部实现 ======================== */

    /** 拿常驻 shell；没有或已死则重建 */
    private PersistentShell ensureShell(String cwd) throws IOException {
        synchronized (shellLock) {
            if (shell != null && shell.alive()) {
                return shell;
            }
            if (shell != null) {
                shell.shutdown(); // 清理残骸
            }
            shell = PersistentShell.spawn(resolveCwd(cwd));
            return shell;
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

    /** 清掉早就结束、前端也不会再问的任务，避免历史表越积越多 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        history.entrySet().removeIf(entry -> {
            ShellJob job = entry.getValue();
            return !job.running && job.finishedAt > 0 && now - job.finishedAt > JOB_KEEP_MILLIS;
        });
    }

    /* ======================== 数据结构 ======================== */

    /** 平台信息 */
    public record ShellInfo(String defaultCwd, String os, String shell) {
    }

    /** 一次命令执行任务（前端认领的句柄） */
    public static class ShellJob {

        public final String id;
        public final String command;
        public final long startedAt = System.currentTimeMillis();

        /** 当前工作目录：随每条命令结束时的哨兵带回，cd 之后立刻反映到前端路径栏 */
        public volatile String cwd;

        /** 本条命令的哨兵 token（泵线程据此切段） */
        volatile String token;

        private final StringBuilder output = new StringBuilder();

        /** 是否因为超过上限而丢过输出 */
        public volatile boolean truncated = false;
        public volatile boolean running = false;
        public volatile Integer exitCode = null;
        public volatile long finishedAt = 0L;

        ShellJob(String id, String command, String cwd) {
            this.id = id;
            this.command = command;
            this.cwd = cwd;
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

        /** 标记任务结束并记录退出码（exitCode 为 null 表示非正常结束/被终止） */
        synchronized void finish(Integer exitCode) {
            this.exitCode = exitCode;
            this.running = false;
            this.finishedAt = System.currentTimeMillis();
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

    /**
     * 常驻 shell：一个长期活着的 bash / cmd，stdin 接收命令、stdout 泵线程持续读。
     *
     * <p>泵线程按行读，逐行判断是否命中当前任务的哨兵；命中则记下退出码、
     * 标记任务结束，然后清空「当前任务」引用，等下一轮。
     * 任务对象本身留在 ShellService.history 里，前端仍可按 jobId 拉完输出。
     */
    static final class PersistentShell {

        private final Process process;
        final BufferedWriter stdin;
        private final Path startCwd;

        /** 当前正在执行的任务；无任务时为 null */
        volatile ShellJob current;

        private PersistentShell(Process process, Path startCwd) throws IOException {
            this.process = process;
            this.startCwd = startCwd;
            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual().name("shell-pump").start(this::pump);
        }

        static PersistentShell spawn(Path cwd) throws IOException {
            // Windows 用 PowerShell（系统自带，非交互管道下不回显命令、不吐提示符，
            // 输出为 UTF-8）；Linux 用 bash。cmd 会回显命令与提示符，故弃用。
            String[] shell = IS_WINDOWS
                    ? new String[]{"powershell", "-NoLogo", "-NoProfile", "-Command", "-"}
                    : new String[]{"bash"};
            ProcessBuilder pb = new ProcessBuilder(shell);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.info("常驻 shell 已启动: shell={}, cwd={}", shell[0], cwd);
            return new PersistentShell(process, cwd);
        }

        boolean alive() {
            return process.isAlive();
        }

        /** 最近一次已知的当前目录（由哨兵带回；尚无则回落到启动目录） */
        volatile String lastCwd;

        String cwdSnapshot() {
            String known = lastCwd;
            return known != null ? known : startCwd.toString();
        }

        /** 泵线程：逐行读 stdout，按哨兵切段 */
        private void pump() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ShellJob job = current;
                    if (job == null) {
                        // 没有认领中的任务（如 shell 启动横幅、上一条命令的残留输出）→ 丢弃
                        continue;
                    }
                    SentinelHit hit = matchSentinel(line, job.token);
                    if (hit != null) {
                        if (!hit.lead().isEmpty()) {
                            // 哨兵与命令最后一段输出粘在同一行：把前面那段补进输出（不补换行）
                            job.append(hit.lead());
                        }
                        job.finish(hit.exitCode());
                        if (hit.cwd() != null && !hit.cwd().isEmpty()) {
                            job.cwd = hit.cwd();
                            lastCwd = hit.cwd();
                        }
                        current = null;
                        continue;
                    }
                    job.append(line);
                    job.append("\n");
                }
            } catch (IOException e) {
                log.warn("读取常驻 shell 输出失败", e);
            } finally {
                ShellJob job = current;
                if (job != null) {
                    job.finish(null);
                    current = null;
                }
            }
        }

        /**
         * 在行内查找指定 token 的哨兵。
         *
         * <p>哨兵形如 `__SB_SHELL_DONE__ <token> <exitCode> <cwd>`。这里用「行内查找」而非
         * 「判断行首」：命令最后一段输出若没有换行（如 `printf abc`、`Write-Host -NoNewline`），
         * 哨兵会与它粘在同一行，此时行首不是哨兵，但它仍是一个完整哨兵、必须被识别，
         * 否则任务会永远等不到结束而卡住。粘在哨兵前的那段文本由 lead 带回，原样补进输出。
         *
         * <p>退出码与路径之间用单个空格分隔，路径内部即使含空格也只在第一个空格处切开，
         * 因此 `0 C:\Program Files` 能被正确还原；退出码为空（PowerShell 纯 cmdlet）时
         * 首段为空字符串，解析为 null。未命中返回 null。
         */
        private SentinelHit matchSentinel(String line, String token) {
            if (token == null || line == null) {
                return null;
            }
            String prefix = DONE_MARK + " " + token + " ";
            int idx = line.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            String lead = line.substring(0, idx);
            String rest = line.substring(idx + prefix.length());
            int sp = rest.indexOf(' ');
            String codeRaw = sp < 0 ? rest : rest.substring(0, sp);
            String cwd = sp < 0 ? null : rest.substring(sp + 1).trim();
            return new SentinelHit(parseExitCode(codeRaw), cwd, lead);
        }

        /** 哨兵解析结果：退出码（可空）+ 命令结束时的当前目录（可空）+ 粘在哨兵前的行内残留 */
        private record SentinelHit(Integer exitCode, String cwd, String lead) {
        }

        /** 解析退出码；解析不出来（如被中断）返回 null */
        private Integer parseExitCode(String raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        void shutdown() {
            try {
                stdin.write("exit");
                stdin.newLine();
                stdin.flush();
            } catch (IOException ignored) {
                // 管道可能已断，忽略
            }
            try {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
