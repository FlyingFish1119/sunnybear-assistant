package com.fishsunny.assistant.engine.tool.service.satellite.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 卫星机 exec 命令的回执结构（CommandResponse.result 字符串的反序列化目标）。
 * <ul>
 *   <li>finished=true：等待窗口内进程已结束，exitCode 有值；</li>
 *   <li>finished=false：进程仍在卫星机运行，exitCode 为 null，
 *       进程结束后由卫星机以 task_done 事件补报最终退出码与全量输出。</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecResult {

    /** 任务 ID（服务端生成，与 CommandTaskRegistry 登记及 task_done 事件同 ID） */
    private String taskId;

    /** 退出码；进程仍在运行时为 null */
    private Integer exitCode;

    /** 输出全量；超限判断与落盘规矩在服务端，与本机 command 路径一致 */
    private String output;

    /** 是否已在等待窗口内结束 */
    private Boolean finished;
}
