package com.fishsunny.satellite.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.satellite.engine.DownloadEngine;
import com.fishsunny.satellite.engine.ExecEngine;
import com.fishsunny.satellite.protocol.CommandRequest;

import java.util.Map;

/**
 * 命令分发器。只做路由，不含任何业务语义。
 * <p>
 * 支持 exec（起进程执行并按等待窗口二段回执）、download（回传单个文件）与
 * cancel_task（终止任务）三个命令。
 */
public class CommandDispatcher {

    private final ExecEngine engine;
    private final DownloadEngine downloadEngine;
    private final ObjectMapper objectMapper;

    public CommandDispatcher(ExecEngine engine, DownloadEngine downloadEngine) {
        this.engine = engine;
        this.downloadEngine = downloadEngine;
        this.objectMapper = new ObjectMapper();
    }

    public String dispatch(CommandRequest cmd) throws Exception {
        CommandRequest.Params p = cmd.params != null
                ? cmd.params : new CommandRequest.Params();

        return switch (cmd.method) {
            case "exec" -> toJson(engine.exec(p));
            case "download" -> toJson(downloadEngine.prepare(p));
            case "cancel_task" -> {
                if (p.taskId == null || p.taskId.isEmpty())
                    yield error("参数 taskId 不能为空");
                // 同一个任务 ID 只属于一类任务，两路都问一遍即可，不需要额外判型
                boolean killedByExec = engine.cancel(p.taskId);
                boolean killedByDownload = downloadEngine.cancel(p.taskId);
                yield toJson(Map.of("killed", killedByExec || killedByDownload));
            }
            default -> error("不支持的命令: " + cmd.method + "。支持: exec, download, cancel_task");
        };
    }

    /**
     * 回执帧已发出后由发送方回调：exec 转后台的任务在此开启输出流式推送，
     * download 的任务在此开启文件分块推送，保证后续 task_log / task_done /
     * file_chunk 等帧一定排在回执帧之后。
     */
    public void afterResponseSent(CommandRequest cmd) {
        if (cmd.params == null) {
            return;
        }
        if ("exec".equals(cmd.method)) {
            engine.afterExecResponseSent(cmd.params.taskId);
        } else if ("download".equals(cmd.method)) {
            downloadEngine.afterResponseSent(cmd.params.taskId);
        }
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
