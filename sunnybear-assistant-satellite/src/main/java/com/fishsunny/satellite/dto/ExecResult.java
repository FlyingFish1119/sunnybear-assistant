package com.fishsunny.satellite.dto;

/**
 * exec 命令的回执结构（序列化为 CommandResponse.result 字符串）。
 * <ul>
 *   <li>finished=true：等待窗口内进程已结束，exitCode 有值；</li>
 *   <li>finished=false：进程仍在运行，exitCode 为 null，
 *       进程结束后以独立 task_done 事件补报最终退出码与输出。</li>
 * </ul>
 * <p>
 * 输出处理规矩只有一套，在服务端（与本机 command 路径完全一致）：全量回传、服务端
 * 判断输出是否超限并落库会话日志。
 * </p>
 */
public class ExecResult {
    public String taskId;
    public Integer exitCode;
    /** 输出全量 */
    public String output;
    public boolean finished;

    public ExecResult() {
    }

    public ExecResult(String taskId, Integer exitCode, String output, boolean finished) {
        this.taskId = taskId;
        this.exitCode = exitCode;
        this.output = output;
        this.finished = finished;
    }
}
