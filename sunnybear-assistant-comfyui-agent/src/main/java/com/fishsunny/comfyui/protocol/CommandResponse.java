package com.fishsunny.comfyui.protocol;

/**
 * JAR 返回给服务端的结果（JAR → WebSocket）
 * <pre>
 * {"id":"uuid","result":"ok"}  或  {"id":"uuid","error":"xxx"}
 * </pre>
 */
public class CommandResponse {
    /** 对应请求的 id */
    public String id;
    /** 执行成功时的返回内容（JSON 字符串） */
    public String result;
    /** 执行失败时的错误信息 */
    public String error;

    /** 注册消息 type */
    public String type;
    /** 设备标识（注册时使用） */
    public String deviceId;
    /** 设备名称（注册时使用） */
    public String deviceName;

    public static CommandResponse register(String deviceId, String deviceName) {
        CommandResponse r = new CommandResponse();
        r.type = "register";
        r.deviceId = deviceId;
        r.deviceName = deviceName;
        return r;
    }

    public static CommandResponse heartbeat() {
        CommandResponse r = new CommandResponse();
        r.type = "heartbeat";
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
}
