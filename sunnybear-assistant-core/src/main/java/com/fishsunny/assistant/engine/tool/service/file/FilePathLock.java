package com.fishsunny.assistant.engine.tool.service.file;

/*
 * @Usage 文件路径细粒度锁管理类 —— 为文件工具的并发修改提供按路径互斥
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/10
 */

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件路径细粒度锁管理类（纯 JDK 实现，不依赖 Spring）
 * <p>
 * 以规范化后的绝对路径为 key，为每个路径维护一把独立 ReentrantLock 与引用计数，
 * 同一路径的写/编辑/删除/下载操作被互斥串行化；引用计数归零时移除条目防内存泄漏。
 * 锁支持重入（ReentrantLock 语义），同一线程重复 acquire 同一路径不会死锁。
 * <p>
 * 用法：
 * <pre>{@code
 * try (FilePathLock.LockHandle lock = FilePathLock.acquire(filePath)) {
 *     // 临界区
 * }
 * }</pre>
 */
public final class FilePathLock {

    /** 路径 key → 锁条目（含引用计数） */
    private static final ConcurrentHashMap<String, Entry> LOCKS = new ConcurrentHashMap<>();

    private FilePathLock() {
    }

    /**
     * 获取指定路径的排他锁（阻塞等待），返回自动释放句柄
     * <p>
     * 锁必须覆盖"路径规范化之后到 finally 之前"的整段临界区（包括 AI 安全检测与用户确认等待），
     * 否则确认期间文件被其他会话修改，确认后写入的仍是陈旧内容。
     */
    public static LockHandle acquire(Path path) {
        String key = toKey(path);
        while (true) {
            Entry entry = LOCKS.computeIfAbsent(key, k -> new Entry());
            entry.lock.lock();                      // 阻塞获取该路径的互斥锁
            if (LOCKS.get(key) == entry) {          // 仍是 map 中登记的当前条目才有效
                entry.refCount++;                   // 引用计数仅在持有 entry.lock 时读写，靠 happens-before 保证可见
                return new LockHandle(key, entry);
            }
            // 条目刚被并发释放并移除，若直接使用会与使用新条目的线程并发进入临界区，解锁重试
            entry.lock.unlock();
        }
    }

    /** 规范化绝对路径转 key；Windows 下忽略大小写（D:\a.txt 与 D:\A.TXT 视为同一把锁） */
    private static String toKey(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? key.toLowerCase(Locale.ROOT) : key;
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int refCount;
    }

    /** 自动释放句柄（try-with-resources 用法） */
    public static final class LockHandle implements AutoCloseable {

        private final String key;
        private final Entry entry;
        private boolean closed;                     // 防重复 close

        private LockHandle(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (--entry.refCount == 0) {
                LOCKS.remove(key, entry);           // 条件移除：仅当仍是同一个条目时才删除，不会误删并发新装的条目
            }
            entry.lock.unlock();
        }
    }
}
