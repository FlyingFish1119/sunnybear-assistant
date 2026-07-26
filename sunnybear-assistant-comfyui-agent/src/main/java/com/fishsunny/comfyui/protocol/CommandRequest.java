package com.fishsunny.comfyui.protocol;

/**
 * 服务端下发的命令（WebSocket → JAR）
 * <pre>
 * {"id":"uuid","method":"generate","params":{...}}
 * </pre>
 */
public class CommandRequest {
    /** 命令 ID，返回结果时原样带回 */
    public String id;
    /** 方法名 */
    public String method;
    /** 方法参数（由各 CommandParams 子类承载） */
    public CommandParams params;

    public static class CommandParams {
        // generate
        public String workflow;
        public Integer timeout;

        // history
        public String promptId;

        // view
        public String filename;
        public String type;       // input / output

        // upload
        public String imageBase64;
        public String uploadFilename;

        // nodes
        public String filter;     // models / loras / samplers / vaes

        // workflows
        public String workflowName;
    }
}
