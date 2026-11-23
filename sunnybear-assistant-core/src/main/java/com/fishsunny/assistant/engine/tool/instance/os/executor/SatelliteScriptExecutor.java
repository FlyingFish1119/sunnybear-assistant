package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 卫星机脚本执行器：经桥接在指定卫星机上执行扩展脚本。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptMeta;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptService;
import com.fishsunny.assistant.satellite.dto.ExecResult;
import com.fishsunny.assistant.satellite.service.SatelliteBridgeService;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卫星机脚本执行器：参数在本机完成 {{}} 渲染，向 {@link SatelliteBridgeService} 发送
 * mode=script 的 exec 请求，渲染后的正文与 scriptType 一并下发。
 * <p>
 * 输出落盘与超限处理与本机路径完全同一套规矩：等待窗口内结束且未超限的输出直接返回全文、
 * 不落任何会话日志；超限才把全量落盘到会话 logs/ 目录并只回文件标记。等待窗口内未结束则
 * 由桥接服务在收到回执时建好会话日志文件并登记后台任务（BRIDGE_SCRIPT），进程运行期间
 * 输出经 task_log 实时追加进日志文件，收尾由 task_done 事件写结束标记并回投。
 * </p>
 */
@Component
public class SatelliteScriptExecutor implements ScriptExecutor {

    private final ExtensionScriptService extensionScriptService;
    private final ExtensionScriptTool.Settings settings;
    private final SatelliteBridgeService satelliteBridgeService;
    private final SessionFileManager sessionFileManager;

    public SatelliteScriptExecutor(ExtensionScriptService extensionScriptService,
                                   @Qualifier(ExtensionScriptTool.SETTINGS) ExtensionScriptTool.Settings settings,
                                   SatelliteBridgeService satelliteBridgeService,
                                   SessionFileManager sessionFileManager) {
        this.extensionScriptService = extensionScriptService;
        this.settings = settings;
        this.satelliteBridgeService = satelliteBridgeService;
        this.sessionFileManager = sessionFileManager;
    }

    @Override
    public String execute(ExtensionScriptMeta script, Map<String, Object> arguments, boolean background, String device, String sessionId) throws Exception {
        try {
            String deviceId = device.trim();
            String title = ExtensionScriptService.scriptTitle(script);

            String content = extensionScriptService.renderScriptBody(script, arguments);
            String taskId = SatelliteBridgeService.newTaskId();
            String logName = "bridge_script_" + taskId + ".log";
            String logRelPath = OSToolKit.buildLogRelativePath(logName);

            Long timeout = settings.getTimeout();
            long waitTimeoutSec = background
                    ? 0 : (timeout != null && timeout > 0 ? timeout : ExtensionScriptTool.DEFAULT_TIMEOUT_SECONDS);

            Map<String, Object> execParams = new LinkedHashMap<>();
            execParams.put("taskId", taskId);
            execParams.put("mode", "script");
            execParams.put("scriptType", script.getType());
            execParams.put("content", content);
            execParams.put("waitTimeoutSec", (int) waitTimeoutSec);

            ExecResult result = satelliteBridgeService.sendExecCommand(deviceId, taskId, sessionId,
                    CommandTaskRegistry.TaskType.BRIDGE_SCRIPT, title, logRelPath, background, execParams);

            if (Boolean.TRUE.equals(result.getFinished())) {
                Integer exitCode = result.getExitCode();
                String fullOutput = result.getOutput() != null ? result.getOutput() : "";
                if (exitCode == null || exitCode != 0) {
                    throw new ToolExecutor.ToolExecuteException(
                            "脚本执行失败，退出码：" + exitCode + "，输出：" + fullOutput);
                }
                // 本机同款规矩：未超限直接返回全文，不落盘；超限全量落会话日志，只回标记
                long outputSize = fullOutput.getBytes(StandardCharsets.UTF_8).length;
                Long maxSize = settings.getMaxOutputSize();
                if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
                    String marker = OSToolKit.persistBridgeLog(sessionFileManager, sessionId, logName, fullOutput);
                    return "脚本输出过大（" + ToolKit.formatSize(outputSize) + "，超过上限 " + ToolKit.formatSize(maxSize)
                            + "），已保存到会话文件：" + marker + "。如需查看完整输出，请用 file_read_tool 读取该文件。";
                }
                return fullOutput;
            }

            // 等待窗口内未结束：桥接服务已在收到回执时建好日志文件并完成 BRIDGE_SCRIPT 登记，
            // 输出正随 task_log 实时追加进该文件——文案与本机路径逐字对齐
            if (background) {
                return "脚本已在卫星机后台启动执行。\n"
                        + "后台任务 ID: " + taskId + "\n"
                        + "输出日志文件: " + SessionFileManager.buildSessionMarker(logRelPath) + "\n"
                        + "> 提示：使用 file_read_tool 读取日志文件内容查看脚本输出。"
                        + "脚本执行完成后，日志末尾会写入退出码和结束时间。\n"
                        + "> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束；\n"
                        + "> 脚本执行完成后，结果会自动投递到本条对话，无需原地等待，可以先去处理别的事情。";
            }
            return "脚本在卫星机执行时间已超过 " + waitTimeoutSec + " 秒，进程没有被终止，已自动转入后台继续运行。\n"
                    + "输出日志文件: " + SessionFileManager.buildSessionMarker(logRelPath) + "\n"
                    + "后台任务 ID: " + taskId + "\n"
                    + "> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束。\n"
                    + "> 请勿重复执行这个脚本。想看进度用 file_read_tool 读取该日志文件；"
                    + "脚本结束后退出码会自动投递到本条对话。";
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("卫星机脚本执行失败: " + e.getMessage());
        }
    }
}
