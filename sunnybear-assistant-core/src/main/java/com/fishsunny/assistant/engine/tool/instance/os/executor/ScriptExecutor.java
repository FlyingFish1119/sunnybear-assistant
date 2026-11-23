package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 脚本执行器抽象：ExtensionScriptTool 只管脚本查找与路由，执行细节归各实现。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptMeta;

import java.util.Map;

/**
 * 脚本执行器：本机与卫星机两条执行路径的统一入口。
 * <p>
 * 约定：{@code device} 非空时由卫星机实现处理，本机实现忽略该参数；
 * 脚本元数据由调用方（ExtensionScriptTool）先行解析校验，实现方只负责执行；
 * 返回给模型的最终文本（输出全文、超限落盘提示或后台运行提示）由实现方直接给出。
 */
public interface ScriptExecutor {

    /**
     * 执行一个扩展脚本。
     *
     * @param script    已解析的脚本元数据
     * @param arguments 脚本参数，用于渲染 {{}} 占位符；可为 null
     * @param background 是否显式后台执行（本机后台运行、卫星机零等待窗口转后台）
     * @param device    目标卫星机 deviceId；本机路径忽略
     * @param sessionId 会话 id，用于日志落盘与后台任务登记
     * @return 给模型的最终文本
     */
    String execute(ExtensionScriptMeta script, Map<String, Object> arguments, boolean background, String device, String sessionId) throws Exception;
}
