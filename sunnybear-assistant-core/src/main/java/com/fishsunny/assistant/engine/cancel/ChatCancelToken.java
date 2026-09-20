package com.fishsunny.assistant.engine.cancel;

import lombok.Getter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/*
 * @Usage 一轮对话的取消令牌 —— 用户「停止」在系统内的唯一载体。
 *
 * 与旧的 PASS_SIGN 静态集合相比，本类的关键差异是取消状态落在对象上而不是「集合里有没有这个 key」：
 * 旧语义下取消 = remove 一个元素，天生不幂等，add/remove 时序一乱（尤其是工具调用循环递归重新 add）
 * 信号就会丢失。这里 cancelled 只从 false 变 true，不可逆，重复 cancel 无副作用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/20
 */
public final class ChatCancelToken {

    /** 取消标志。只从 false 变 true，不可逆 —— cancel 幂等的根据 */
    @Getter
    private volatile boolean cancelled;

    /**
     * 本轮正在执行工具任务的线程，取消时逐个 interrupt 实现硬中断。
     * 工具任务跑在虚拟线程上，对 interrupt 的响应比平台线程池好得多（阻塞 IO 可被打断）。
     */
    private final Set<Thread> runningThreads = ConcurrentHashMap.newKeySet();

    /**
     * 取消时需要立即执行的唤醒动作（如清空收流队列、投递取消事件）。
     * 动作在调用 cancel 的线程上执行，必须快速返回、不抛异常，且要幂等。
     */
    private final List<Runnable> wakeups = new CopyOnWriteArrayList<>();

    /**
     * 取消本轮。幂等：重复调用只生效一次。
     * 先跑唤醒动作把阻塞中的主线程放出来，再 interrupt 正在执行工具的线程。
     */
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable wakeup : wakeups) {
            try {
                wakeup.run();
            } catch (Exception e) {
                // 唤醒动作失败不能反过来打断取消流程，后面的线程中断照常执行
            }
        }
        wakeups.clear();
        for (Thread thread : runningThreads) {
            thread.interrupt();
        }
    }

    /**
     * 注册取消唤醒动作：已取消则立即执行，否则挂起等取消时执行。
     * 注册与取消并发时可能补跑一次，故动作必须幂等。
     */
    public void onCancel(Runnable wakeup) {
        if (wakeup == null) {
            return;
        }
        wakeups.add(wakeup);
        if (cancelled) {
            wakeups.remove(wakeup);
            wakeup.run();
        }
    }

    /** 注销唤醒动作。本轮收流结束时调用，避免动作残留在共享令牌上被后续链路触发 */
    public void offCancel(Runnable wakeup) {
        if (wakeup != null) {
            wakeups.remove(wakeup);
        }
    }

    /** 登记正在执行工具任务的线程，供 cancel 时硬中断 */
    public void registerThread(Thread thread) {
        if (thread != null) {
            runningThreads.add(thread);
        }
    }

    public void unregisterThread(Thread thread) {
        if (thread != null) {
            runningThreads.remove(thread);
        }
    }
}
