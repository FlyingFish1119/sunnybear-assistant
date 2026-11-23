package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 命令执行器抽象：CommandTool 只管安全审查与路由，执行细节归各实现。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

/**
 * 命令执行器：本机与卫星机两条执行路径的统一入口。
 * <p>
 * 约定：{@code device} 非空时由卫星机实现处理，本机实现忽略该参数；
 * 返回给模型的最终文本（输出全文、超限落盘提示或后台运行提示）由实现方直接给出。
 */
public interface CommandExecutor {

    /**
     * 执行一条命令。
     *
     * @param command   要执行的命令
     * @param background 是否显式后台执行（本机后台运行、卫星机零等待窗口转后台）
     * @param device    目标卫星机 deviceId；本机路径忽略
     * @param sessionId 会话 id，用于日志落盘与后台任务登记
     * @return 给模型的最终文本
     */
    String execute(String command, boolean background, String device, String sessionId) throws Exception;
}
