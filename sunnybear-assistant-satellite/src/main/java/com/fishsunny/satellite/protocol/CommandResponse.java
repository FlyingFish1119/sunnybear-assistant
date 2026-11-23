package com.fishsunny.satellite.protocol;

/**
 * 卫星端发给服务端的消息（卫星 → WebSocket）
 * <pre>
 * 命令回执：{"id":"uuid","result":"..."} 或 {"id":"uuid","error":"..."}
 * 注册消息：{"type":"register","deviceId":"...","deviceName":"..."}
 * 实时日志：{"type":"task_log","deviceId":"...","taskId":"...","chunk":"..."}
 * 完成事件：{"type":"task_done","deviceId":"...","taskId":"...","exitCode":0}
 * </pre>
 */
public class CommandResponse {
    /** 对应请求的 id（命令回执用） */
    public String id;
    /** 执行成功时的返回内容（JSON 字符串） */
    public String result;
    /** 执行失败时的错误信息 */
    public String error;

    /** 消息类型：register / task_log / task_done */
    public String type;
    /** 设备标识（register / task_log / task_done 用） */
    public String deviceId;
    /** 设备名称（register 用） */
    public String deviceName;

    /** 任务 ID（task_log / task_done 用） */
    public String taskId;
    /** 退出码（task_done 用） */
    public Integer exitCode;
    /** 输出全量（exec 回执里为等待窗口内已产出的完整 UTF-8 前缀；task_done 不再携带，输出已全部经 task_log 实时推完） */
    public String output;
    /** 增量日志片段（task_log 用） */
    public String chunk;

    public static CommandResponse register(String deviceId, String deviceName) {
        CommandResponse r = new CommandResponse();
        r.type = "register";
        r.deviceId = deviceId;
        r.deviceName = deviceName;
        return r;
    }

    public static CommandResponse success(String id, String result) {
        CommandResponse r = new CommandResponse();
        r.id = id;
        r.result = result;
        return r;
    }

    public static CommandResponse failure(String id, String error) {
        CommandResponse r = new CommandResponse();
        r.id = id;
        r.error = error;
        return r;
    }

    /** 后台任务实时增量日志事件：任务转后台后每凑齐一段完整 UTF-8 输出即推送一次，服务端实时追加进会话日志文件 */
    public static CommandResponse taskLog(String deviceId, String taskId, String chunk) {
        CommandResponse r = new CommandResponse();
        r.type = "task_log";
        r.deviceId = deviceId;
        r.taskId = taskId;
        r.chunk = chunk;
        return r;
    }

    /** 后台任务完成事件：输出已通过 ExecResult 与 task_log 全部实时推送，这里只补报最终退出码 */
    public static CommandResponse taskDone(String deviceId, String taskId, Integer exitCode) {
        CommandResponse r = new CommandResponse();
        r.type = "task_done";
        r.deviceId = deviceId;
        r.taskId = taskId;
        r.exitCode = exitCode;
        return r;
    }
}
