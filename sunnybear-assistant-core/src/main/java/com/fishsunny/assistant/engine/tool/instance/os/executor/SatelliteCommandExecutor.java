package com.fishsunny.assistant.engine.tool.instance.os.executor;

/*
 * @Usage 卫星机命令执行器：经桥接在指定卫星机上执行命令。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.engine.tool.service.satellite.dto.ExecResult;
import com.fishsunny.assistant.engine.tool.service.satellite.service.SatelliteBridgeService;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卫星机命令执行器：向 {@link SatelliteBridgeService} 发送 mode=command 的 exec 请求。
 * <p>
 * 输出落盘与超限处理与本机路径完全同一套规矩：等待窗口内结束且未超限的输出直接返回全文、
 * 不落任何会话日志；超限才把全量落盘到会话 logs/ 目录并只回文件标记。等待窗口内未结束则
 * 由桥接服务在收到回执时建好会话日志文件并登记后台任务（BRIDGE_COMMAND），进程运行期间
 * 输出经 task_log 实时追加进日志文件，收尾由 task_done 事件写结束标记并回投。
 * </p>
 */
@Component
public class SatelliteCommandExecutor implements CommandExecutor {

    private final CommandTool.Settings settings;
    private final SatelliteBridgeService satelliteBridgeService;
    private final SessionFileManager sessionFileManager;

    public SatelliteCommandExecutor(@Qualifier(CommandTool.SETTINGS) CommandTool.Settings settings,
                                    SatelliteBridgeService satelliteBridgeService,
                                    SessionFileManager sessionFileManager) {
        this.settings = settings;
        this.satelliteBridgeService = satelliteBridgeService;
        this.sessionFileManager = sessionFileManager;
    }

    @Override
    public String execute(String command, boolean background, String device, String sessionId) throws Exception {
        try {
            String deviceId = device.trim();

            long timeout = settings.getTimeout() != null && settings.getTimeout() > 0
                    ? settings.getTimeout() : CommandTool.DEFAULT_TIMEOUT_SECONDS;
            // 显式后台：等待窗口直接给 0（立即转后台）；否则按命令超时设置等待
            int waitTimeoutSec = background ? 0 : (int) timeout;

            String taskId = SatelliteBridgeService.newTaskId();
            String logName = "bridge_command_" + taskId + ".log";
            String logRelPath = OSToolKit.buildLogRelativePath(logName);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskId", taskId);
            params.put("mode", "command");
            params.put("content", command);
            params.put("waitTimeoutSec", waitTimeoutSec);

            ExecResult result = satelliteBridgeService.sendExecCommand(deviceId, taskId, sessionId,
                    CommandTaskRegistry.TaskType.BRIDGE_COMMAND, command, logRelPath, background, params);

            if (Boolean.TRUE.equals(result.getFinished())) {
                Integer exitCode = result.getExitCode();
                String fullOutput = result.getOutput() != null ? result.getOutput() : "";
                if (exitCode == null || exitCode != 0) {
                    throw new ToolExecutor.ToolExecuteException(
                            "命令执行失败，退出码：" + exitCode + "，输出：" + fullOutput);
                }
                // 本机同款规矩：未超限直接返回全文，不落盘；超限全量落会话日志，只回标记
                long outputSize = fullOutput.getBytes(StandardCharsets.UTF_8).length;
                Long maxSize = settings.getMaxOutputSize();
                if (maxSize != null && maxSize > 0 && outputSize > maxSize) {
                    String marker = OSToolKit.persistBridgeLog(sessionFileManager, sessionId, logName, fullOutput);
                    return "命令输出过大（" + ToolKit.formatSize(outputSize) + "，超过上限 " + ToolKit.formatSize(maxSize)
                            + "），已保存到会话文件：" + marker + "。如需查看完整输出，请用 file_read_tool 读取该文件。";
                }
                return fullOutput;
            }

            // 等待窗口内未结束：桥接服务已在收到回执时建好日志文件并完成 BRIDGE_COMMAND 登记，
            // 输出正随 task_log 实时追加进该文件——文案与本机路径逐字对齐
            if (background) {
                return "命令已在卫星机后台启动执行。\n"
                        + "后台任务 ID: " + taskId + "\n"
                        + "输出日志文件: " + SessionFileManager.buildSessionMarker(logRelPath) + "\n"
                        + "> 提示：使用 file_read_tool 读取日志文件内容查看命令输出。"
                        + "命令执行完成后，日志末尾会写入退出码和结束时间。\n"
                        + "> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束；\n"
                        + "> 命令执行完成后，结果会自动投递到本条对话，无需原地等待，可以先去处理别的事情。";
            }
            return "命令在卫星机执行时间已超过 " + waitTimeoutSec + " 秒，进程没有被终止，已自动转入后台继续运行。\n"
                    + "输出日志文件: " + SessionFileManager.buildSessionMarker(logRelPath) + "\n"
                    + "后台任务 ID: " + taskId + "\n"
                    + "> 可用 wait_flow_tool 的 eventId 填这个 ID 等待任务结束。\n"
                    + "> 请勿重复执行这条命令。想看进度用 file_read_tool 读取该日志文件；"
                    + "命令结束后退出码与日志尾部会自动投递到本条对话。";
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("卫星机命令执行失败: " + e.getMessage());
        }
    }
}
