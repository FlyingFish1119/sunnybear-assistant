package com.fishsunny.satellite.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.satellite.engine.ExecEngine;
import com.fishsunny.satellite.protocol.CommandRequest;

import java.util.Map;

/**
 * 命令分发器。只做路由，不含任何业务语义。
 * <p>
 * 支持 exec（起进程执行并按等待窗口二段回执）与 cancel_task（终止任务）两个命令。
 */
public class CommandDispatcher {

    private final ExecEngine engine;
    private final ObjectMapper objectMapper;

    public CommandDispatcher(ExecEngine engine) {
        this.engine = engine;
        this.objectMapper = new ObjectMapper();
    }

    public String dispatch(CommandRequest cmd) throws Exception {
        CommandRequest.Params p = cmd.params != null
                ? cmd.params : new CommandRequest.Params();

        return switch (cmd.method) {
            case "exec" -> toJson(engine.exec(p));
            case "cancel_task" -> {
                if (p.taskId == null || p.taskId.isEmpty())
                    yield error("参数 taskId 不能为空");
                yield toJson(Map.of("killed", engine.cancel(p.taskId)));
            }
            default -> error("不支持的命令: " + cmd.method + "。支持: exec, cancel_task");
        };
    }

    /**
     * 回执帧已发出后由发送方回调：exec 转后台的任务在此开启输出流式推送，
     * 保证后续 task_log / task_done 帧一定排在 exec 回执帧之后。
     */
    public void afterResponseSent(CommandRequest cmd) {
        if ("exec".equals(cmd.method) && cmd.params != null) {
            engine.afterExecResponseSent(cmd.params.taskId);
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
