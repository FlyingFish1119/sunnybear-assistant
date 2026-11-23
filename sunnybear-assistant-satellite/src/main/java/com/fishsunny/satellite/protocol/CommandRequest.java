package com.fishsunny.satellite.protocol;

/**
 * 服务端下发的命令（WebSocket → 卫星端）
 * <pre>
 * {"id":"uuid","method":"exec","params":{"taskId":"...","mode":"command","content":"..."}}
 * {"id":"uuid","method":"cancel_task","params":{"taskId":"..."}}
 * </pre>
 */
public class CommandRequest {
    /** 命令 ID，返回结果时原样带回 */
    public String id;
    /** 方法名：exec / cancel_task */
    public String method;
    /** 方法参数 */
    public Params params;

    public static class Params {
        /** 任务 ID（服务端生成；exec 与 cancel_task 共用） */
        public String taskId;

        // ---- exec ----
        /** 执行模式：command（本机 shell 跑命令串）| script（落盘后按 scriptType 起进程） */
        public String mode;
        /** 仅 mode=script 必填：cmd / powershell / python / bash */
        public String scriptType;
        /** 命令串（mode=command）或渲染后的脚本正文（mode=script） */
        public String content;
        /** 等待窗口秒数：窗口内进程结束则同步回执，否则立即回执转后台；0 = 立即转后台 */
        public Integer waitTimeoutSec;
    }
}
