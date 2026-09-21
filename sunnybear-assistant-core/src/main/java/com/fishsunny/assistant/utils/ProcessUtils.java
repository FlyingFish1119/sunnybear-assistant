package com.fishsunny.assistant.utils;

/*
 * @Usage 进程收尾工具。
 *
 *        为什么要有这个方法：Process.destroyForcibly() 只杀它自己那一个进程。
 *        在 Windows 上 `cmd.exe /c mvn ...` 真正干活的是派生出来的 java.exe，
 *        杀掉 cmd.exe 对它没有任何影响 —— 表现就是「工具已经报超时了，
 *        命令却在后台继续跑，过一会儿文件真的被改完了」。
 *        Linux 上 `bash -c xxx` 同理。
 *
 *        所以杀进程必须杀整棵树。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/21
 */

import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public final class ProcessUtils {

    private ProcessUtils() {
    }

    /**
     * 连同子孙进程一起强杀。
     * <p>
     * 先给子孙拍快照再动手：父进程一旦死掉，再去遍历它的 descendants 就什么都查不到了，
     * 剩下那批子进程会变成没人管的孤儿。传 null 或已退出的进程是安全的空操作。
     */
    public static void killTree(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        } catch (Exception e) {
            // 进程可能刚好退出，拿不到子孙列表不影响后面杀本体
            descendants = List.of();
        }
        descendants.forEach(ProcessUtils::destroyQuietly);
        destroyQuietly(process.toHandle());
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("结束进程失败: pid={}", handle.pid(), e);
        }
    }
}
