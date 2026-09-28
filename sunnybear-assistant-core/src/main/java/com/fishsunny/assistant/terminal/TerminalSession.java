package com.fishsunny.assistant.terminal;

/*
 * @Usage 本地 PTY 终端会话 —— 前端「终端」面板的真正后端。
 *
 *        和旧的 ShellService（常驻 shell + 哨兵切段）是两个层次的东西：
 *        旧方案没有 PTY，只能跑「有始有终、无交互」的命令，vim / top / REPL 必卡；
 *        这里通过 pty4j 拉起一个带伪终端的真实 shell 进程，内核侧就是一个 tty，
 *        交互式程序因此能正常工作。
 *
 *        平台：
 *        · Windows：ConPTY + PowerShell（Windows 10 1809+ 自带 ConPTY），开启 ANSI 颜色；
 *        · Linux/macOS：用户登录 shell（$SHELL，缺省 /bin/bash）以 -l 登录模式启动。
 *
 *        数据流：一个虚拟线程持续从 PTY 读原始字节，交给回调（由 WS handler 打成二进制帧下发）；
 *        前端发来的二进制帧经 write() 原样写进 PTY。终端尺寸通过 setWinSize 透传给内核，
 *        因此 vim 等全屏程序能拿到正确的行列数。
 *
 *        生命周期：WS 连接建立即 start，连接关闭/进程退出即 close（杀整棵进程树，
 *        避免 mvn 这类会派生子进程的命令留下孤儿）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28
 */

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

@Slf4j
public final class TerminalSession {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 读 PTY 的缓冲区大小 */
    private static final int READ_BUFFER_SIZE = 8 * 1024;

    private final PtyProcess process;
    private final OutputStream stdin;
    private final Consumer<byte[]> onOutput;
    private final IntConsumer onExit;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TerminalSession(PtyProcess process, Consumer<byte[]> onOutput, IntConsumer onExit) {
        this.process = process;
        this.onOutput = onOutput;
        this.onExit = onExit;
        this.stdin = process.getOutputStream();
        Thread.ofVirtual().name("terminal-pty-reader").start(this::pump);
    }

    /**
     * 拉起一个 PTY 会话。
     *
     * @param cols     初始列数
     * @param rows     初始行数
     * @param onOutput 收到 PTY 输出时的回调（原始字节，由调用方负责发送与异常兜底）
     * @param onExit   进程结束时的回调，参数为退出码（-1 表示拿不到）
     */
    public static TerminalSession start(int cols, int rows,
                                        Consumer<byte[]> onOutput,
                                        IntConsumer onExit) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        String[] command = IS_WINDOWS
                ? new String[]{"powershell.exe", "-NoLogo"}
                : new String[]{resolveShell(), "-l"};

        PtyProcessBuilder builder = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.dir"))
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setRedirectErrorStream(true);

        if (IS_WINDOWS) {
            // ConPTY：新式 Windows 控制台，ANSI 颜色与交互体验都远好于旧 winpty
            builder.setUseWinConPty(true);
            builder.setWindowsAnsiColorEnabled(true);
        }

        PtyProcess process = builder.start();
        log.info("本地终端已启动: shell={}, cols={}, rows={}", command[0], cols, rows);
        return new TerminalSession(process, onOutput, onExit);
    }

    /** 取用户登录 shell；拿不到就回落 bash */
    private static String resolveShell() {
        String shell = System.getenv("SHELL");
        return (shell == null || shell.isBlank()) ? "/bin/bash" : shell;
    }

    /** 把用户输入原样写进 PTY */
    public void write(byte[] data) {
        if (data == null || data.length == 0 || closed.get()) {
            return;
        }
        synchronized (this) {
            try {
                stdin.write(data);
                stdin.flush();
            } catch (IOException e) {
                log.debug("终端写入失败: {}", e.getMessage());
                close();
            }
        }
    }

    /** 调整 PTY 尺寸（前端 resize 时调用） */
    public void resize(int cols, int rows) {
        if (closed.get() || cols <= 0 || rows <= 0) {
            return;
        }
        try {
            process.setWinSize(new WinSize(cols, rows));
        } catch (Exception e) {
            log.debug("终端调整尺寸失败: cols={}, rows={}, err={}", cols, rows, e.getMessage());
        }
    }

    /** 关闭会话：强杀整棵进程树并停止读取线程 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long pid = -1;
        try {
            pid = process.pid();
        } catch (Exception ignored) {
            // 拿不到 pid 也无妨，下面还有 destroy 兜底
        }
        // pty4j 的进程实现不支持 Process.toHandle()，因此走不了 ProcessUtils.killTree；
        // 改用平台命令按 PID 收掉整棵进程树，避免 mvn 这类命令留下派生进程
        if (pid > 0) {
            killTreeByPid(pid);
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
            // PTY 可能已随进程退出而失效，忽略
        }
    }

    /**
     * 按 PID 收整棵进程树。
     * Windows 用 taskkill /T；Unix 用 kill 打进程组（pty4j 的子进程是会话组长，pgid == pid）。
     * 都只是尽力而为：拿不到 pid 或命令不存在时，仍有 {@code process.destroy()} 兜底。
     */
    private void killTreeByPid(long pid) {
        try {
            ProcessBuilder pb = IS_WINDOWS
                    ? new ProcessBuilder("taskkill", "/F", "/T", "/PID", Long.toString(pid))
                    : new ProcessBuilder("kill", "-TERM", "-" + pid);
            pb.redirectErrorStream(true).start();
        } catch (Exception e) {
            log.debug("按 PID 结束终端进程树失败: pid={}, err={}", pid, e.getMessage());
        }
    }

    /** 读线程：持续把 PTY 输出交给回调，进程结束后触发 onExit */
    private void pump() {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buffer, 0, chunk, 0, n);
                    safely(() -> onOutput.accept(chunk));
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.debug("终端输出读取结束: {}", e.getMessage());
            }
        } finally {
            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            close();
            int code = exitCode;
            safely(() -> onExit.accept(code));
        }
    }

    /** 回调里的异常不该拖垮读线程：吞掉并记 debug（连接多半已断开） */
    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("终端回调执行失败: {}", e.getMessage());
        }
    }
}
