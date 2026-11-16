package com.fishsunny.assistant.engine.tool.service.background;

/*
 * @Usage 后台命令 / 扩展脚本的运行时登记表。
 *
 *        只登记「真正在后台跑」的任务：显式 background=true 的，以及前台跑超时自动转后台的。
 *        前台正常跑完的那条路径不登记 —— 它跑完就把日志删了，登记了也只会给前端留一条读不到内容的空壳。
 *
 *        任务一结束就从表里摘掉（finish），前端靠轮询 list 感知「还在跑 / 已经没了」，
 *        不需要额外的完成态字段。日志本体留在 session/{id}/file/logs/ 下，随时可翻。
 *
 *        摘掉的同时会唤醒所有正等这个 id 的等待者（wait_flow_tool 的 eventId 模式）：
 *        每个任务自带一个 CountDownLatch，摘除方 countDown，等待方 await。一个 latch 可被
 *        多个线程同时等待，天然一对多，不必另建订阅表；等待方持有的是同一个 latch 引用，
 *        所以摘除发生在它调用 await 之前还是之后都不会漏通知。表里查不到 id 一律视作「已完成」。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/11
 */

import com.fishsunny.assistant.utils.ProcessUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台任务登记表（进程内，单例）。
 * <p>
 * 职责：登记、列出来、杀掉、摘掉，以及摘掉时唤醒等待者。日志内容的读取交给 REST 层按 logPath 去磁盘上取，
 * 这里不持有任何字节 —— 长跑命令的输出可能有几百 MB，放进内存表里迟早把堆撑爆。
 * <p>
 * 进程重启即清空：这张表描述的是「此刻还活着的子进程」，进程没了子进程也没了，
 * 重建一张空表才是正确状态，不需要落库。
 */
@Slf4j
@Component
public class CommandTaskRegistry {

    /** 任务来源：命令行走的是 COMMAND，扩展脚本走 SCRIPT，前端据此显示不同图标与文案 */
    public enum TaskType {
        COMMAND, SCRIPT
    }

    /** 任务 ID -> 运行时信息 */
    private final Map<String, RunningTask> tasks = new ConcurrentHashMap<>();

    /**
     * 登记一个刚开始跑的后台任务。
     *
     * @param sessionId 归属会话，前端按当前会话过滤
     * @param type      任务来源
     * @param title     展示标题：命令原文，或「脚本名 (类型)」
     * @param logPath   日志相对会话文件目录的路径（{@code logs/xxx.log}）
     * @param process   已启动的子进程，供结束操作杀进程树
     * @return 登记后的运行时信息；sessionId 为空时返回 null（没地方归属，不登记）
     */
    public RunningTask register(String sessionId, TaskType type, String title, String logPath, Process process) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RunningTask task = new RunningTask(id, sessionId, type, title, logPath, process);
        tasks.put(id, task);
        log.info("后台任务已登记: id={}, type={}, sessionId={}", id, type, sessionId);
        return task;
    }

    /**
     * 摘掉一个任务并唤醒所有等待它的线程。所有「任务终结」出口（自然收尾、异常、手动 kill）
     * 都必须经过这里，保证等待者不会卡死。幂等 —— 重复调用是安全的空操作。
     */
    private void signal(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        RunningTask task = tasks.remove(taskId);
        if (task != null) {
            task.getDone().countDown();
            log.info("后台任务已移除并唤醒等待者: id={}", taskId);
        }
    }

    /**
     * 任务收尾后摘掉并唤醒等待者。幂等 —— 被用户提前结束的任务已经从表里删过一次，
     * 收尾线程再调一次是安全的空操作。
     *
     * @param taskId 任务 ID，可空
     */
    public void finish(String taskId) {
        signal(taskId);
    }

    /**
     * 等待某个后台任务结束，供 wait_flow_tool 的 eventId 模式使用。
     * <p>
     * 表里查不到这个 id（已经跑完、被手动结束、进程重启清空、或压根不存在）一律视作「已完成」，
     * 立即返回 true —— 不区分「真完成」和「本来就没有」，调用方只关心「还要不要继续等」。
     * <p>
     * 一个 latch 可被多个线程同时 await，天然支持「通知所有关注这个 id 的等待者」。
     * 等待者先拿到 RunningTask 引用，latch 是它的 final 字段，摘除方的 countDown 落在同一个对象上，
     * 所以即便 countDown 发生在等待者调用 await 之前，也不会漏通知。
     *
     * @param taskId        任务 ID
     * @param timeoutMillis 最长等待毫秒数；小于等于 0 表示不限时，一直等到任务结束
     * @return true 表示任务已结束（或查不到）；false 表示超时、任务仍在运行
     * @throws InterruptedException 等待线程被中断（对话取消）
     */
    public boolean await(String taskId, long timeoutMillis) throws InterruptedException {
        if (!StringUtils.hasText(taskId)) {
            return true;
        }
        RunningTask task = tasks.get(taskId);
        if (task == null) {
            return true;
        }
        CountDownLatch done = task.getDone();
        if (timeoutMillis <= 0) {
            done.await();
            return true;
        }
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 按会话 + 任务 ID 取运行时信息。会话对不上视为不存在，防止跨会话误杀别人的进程。
     */
    public RunningTask get(String sessionId, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        RunningTask task = tasks.get(taskId);
        if (task == null) {
            return null;
        }
        return task.getSessionId().equals(sessionId) ? task : null;
    }

    /**
     * 列出任务。sessionId 为空时返回全部（供排查用），一般前端都会带上当前会话。
     * <p>按启动时间升序，前端拿到的顺序与「谁先跑的」一致，不会因为 Map 无序而乱跳。
     */
    public List<RunningTask> list(String sessionId) {
        return tasks.values().stream()
                .filter(task -> !StringUtils.hasText(sessionId) || task.getSessionId().equals(sessionId))
                .sorted(Comparator.comparingLong(RunningTask::getStartTime))
                .toList();
    }

    /**
     * 结束一个后台任务：杀整棵进程树并立即从表里摘掉（同时唤醒等待者）。
     * <p>
     * 先打 killed 标记再动手，收尾线程据此把日志 footer 和回投文案写成「已被用户手动结束」，
     * 而不是一句含糊的「退出码 1」—— 那种文案会让人以为是命令自己失败的。
     *
     * @return true 表示确实杀掉了一个活着的进程；false 表示任务不存在、会话不符或进程已经退出
     */
    public boolean kill(String sessionId, String taskId) {
        RunningTask task = get(sessionId, taskId);
        if (task == null) {
            return false;
        }
        Process process = task.getProcess();
        if (process == null || !process.isAlive()) {
            signal(taskId);
            return false;
        }
        task.getKilled().set(true);
        ProcessUtils.killTree(process);
        signal(taskId);
        log.info("后台任务已被手动结束: id={}, sessionId={}", taskId, sessionId);
        return true;
    }

    /** 运行时任务信息 */
    @Getter
    public static class RunningTask {

        /** 任务 ID（随机短串，进程内登记表用） */
        private final String id;

        /** 归属会话 */
        private final String sessionId;

        /** 任务来源 */
        private final TaskType type;

        /** 展示标题 */
        private final String title;

        /** 日志相对会话文件目录的路径 */
        private final String logPath;

        /** 子进程，供结束操作杀树 */
        private final Process process;

        /** 启动时间（毫秒时间戳） */
        private final long startTime;

        /** 是否已被用户手动结束；收尾线程据此改写日志与回投文案 */
        private final AtomicBoolean killed = new AtomicBoolean(false);

        /** 结束信号：摘除时 countDown，供 await 的等待者感知；一个 latch 支持多个等待者 */
        private final CountDownLatch done = new CountDownLatch(1);

        RunningTask(String id, String sessionId, TaskType type, String title, String logPath, Process process) {
            this.id = id;
            this.sessionId = sessionId;
            this.type = type;
            this.title = title;
            this.logPath = logPath;
            this.process = process;
            this.startTime = System.currentTimeMillis();
        }
    }
}
