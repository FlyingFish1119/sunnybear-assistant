package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage 卫星机文件下载工具 - 经卫星桥接通道把卫星机上的单个文件拉回本机保存
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import com.fishsunny.assistant.engine.tool.service.satellite.service.SatelliteBridgeService;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 卫星机文件下载工具：向桥接服务下发 download 命令，由卫星机分块回传，本机顺序落盘。
 * <p>
 * 与 {@link FileDownloadTool} 的分工：那个从 URL 下载，这个从卫星机的本地磁盘取文件。
 * 传输不设超时，卫星机拒绝、传输出错或连接断开才会失败；path 必须显式给出，不做确认。
 * </p>
 */
@ToolKitComponent(FileToolKit.class)
@ConditionalOnExpression("${engine.tool.file.enable:true} && ${engine.tool.file.satellite-download.enable:true}")
public class SatelliteDownloadTool implements ToolHandler {

    public static final String NAME = "satellite_download_tool";

    private final ObjectMapper objectMapper;
    private final SatelliteBridgeService satelliteBridgeService;
    private final SessionFileManager sessionFileManager;

    public SatelliteDownloadTool(ObjectMapper objectMapper,
                                 SatelliteBridgeService satelliteBridgeService,
                                 SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.satelliteBridgeService = satelliteBridgeService;
        this.sessionFileManager = sessionFileManager;
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析失败: " + e.getMessage());
        }
        if (!StringUtils.hasText(arguments.getDevice())) {
            throw new ToolExecutor.ToolExecuteException("参数 device 不能为空");
        }
        if (!StringUtils.hasText(arguments.getRemotePath())) {
            throw new ToolExecutor.ToolExecuteException("参数 remotePath 不能为空");
        }
        if (!StringUtils.hasText(arguments.getPath())) {
            throw new ToolExecutor.ToolExecuteException("参数 path 不能为空");
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();

        // 支持 sessionId:相对路径 指代当前会话沙箱，其余按文件系统路径处理
        Path savePath = sessionFileManager.resolveToolPath(arguments.getPath(), sessionId).path();
        Path parent = savePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new ToolExecutor.ToolExecuteException("无法创建父目录: " + parent + "，" + e.getMessage());
            }
        }

        SatelliteBridgeService.DownloadResult result = satelliteBridgeService.download(
                arguments.getDevice().trim(), arguments.getRemotePath().trim(), savePath);

        StringBuilder sb = new StringBuilder();
        sb.append("文件已从卫星机下载完成\n\n");
        sb.append("卫星机: ").append(result.deviceId()).append("\n");
        sb.append("远端文件: ").append(result.remotePath()).append("\n");
        sb.append("保存路径: ").append(result.savePath()).append("\n");
        sb.append("文件大小: ").append(ToolKit.formatSize(result.size()))
                .append("（").append(result.size()).append(" 字节）\n");
        sb.append(String.format("耗时: %.1f 秒", result.costMs() / 1000.0)).append("\n");
        return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return new ToolRegister()
                .setName(NAME)
                .setDescription("从卫星机（远程设备）把单个文件传回本机保存。device 填卫星机 deviceId（如 FlyingFish），"
                        + "remotePath 为卫星机上的文件绝对路径，path 为本机保存路径（必填，含文件名）。"
                        + "传输完成前会一直等待，不设超时。")
                .setRequired(List.of("device", "remotePath", "path"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("device", "string", "目标卫星机 deviceId，例如 FlyingFish"),
                        new ToolRegister.Parameters("remotePath", "string", "卫星机上的文件绝对路径，例如 D:\\models\\test.safetensors"),
                        new ToolRegister.Parameters("path", "string", "本机保存路径（必填），绝对路径或 sessionId:相对路径，例如 E:\\downloads\\test.safetensors")
                ));
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Arguments {
        private String device;
        private String remotePath;
        private String path;
    }
}
