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
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/11
 */

import com.fishsunny.assistant.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台任务登记表（进程内，单例）。
 * <p>
 * 职责只有四件事：登记、列出来、杀掉、摘掉。日志内容的读取交给 REST 层按 logPath 去磁盘上取，
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
     * 任务收尾后摘掉。幂等 —— 被用户提前结束的任务已经从表里删过一次，收尾线程再调一次是安全的空操作。
     *
     * @param taskId 任务 ID，可空
     */
    public void finish(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        if (tasks.remove(taskId) != null) {
            log.info("后台任务已移除: id={}", taskId);
        }
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
     * 结束一个后台任务：杀整棵进程树并立即从表里摘掉。
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
            tasks.remove(taskId);
            return false;
        }
        task.getKilled().set(true);
        ProcessUtils.killTree(process);
        tasks.remove(taskId);
        log.info("后台任务已被手动结束: id={}, sessionId={}", taskId, sessionId);
        return true;
    }

    /** 运行时任务信息 */
    @lombok.Getter
    public static class RunningTask {

        /** 任务 ID（随机短串，不复用） */
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
