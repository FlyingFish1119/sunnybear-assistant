package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.instance.file.FileDeleteTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileDownloadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileEditTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileWriteTool;
import com.fishsunny.assistant.engine.tool.instance.image.ImageCaptionTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebReaderTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebSearchTool;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 tool_settings.json 文件，
 * 并按工具类型分别装配为 Spring Bean。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class ToolSettingsLoader {

    private static final String TOOL_SETTINGS_JSON = "tool_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public ToolSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private Map<String, Object> toolSettingsCache = null;

    private void initToolSettingsFile() {
        synchronized (this) {
            if (toolSettingsCache != null) {
                return;
            }
            File settingsFile = new File(basePath + "/" + TOOL_SETTINGS_JSON);
            if (!settingsFile.exists()) {
                log.warn("工具设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
            if (!settingsFile.isFile()) {
                log.warn("工具设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
            try {
                JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                toolSettingsCache = objectMapper.readValue(settingsFile, mapType);
            } catch (IOException e) {
                log.warn("工具设置文件读取失败: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
        }
    }

    private Map<String, Object> loadDefaultToolSettings() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(CommandTool.SETTINGS, new CommandTool.Settings(CommandTool.AUTO)
                .setWhiteList(List.of(
                        "dir", "echo", "cd", "type", "set", "help", "ver", "date", "time",
                        "whoami", "hostname", "ipconfig", "nslookup", "netstat", "tasklist",
                        "where", "findstr", "tree", "cls", "path", "assoc", "ftype"
                ))
                .setBlackList(List.of(
                        "rmdir /s", "del /f", "del /s", "format", "diskpart",
                        "reg delete", "reg add", "bcdedit", "shutdown", "netsh",
                        "icacls", "takeown", "cacls", "sc delete", "wmic delete"
                ))
                .setMaxOutputSize(32768L).setSafetyOutputSize(8192L));
        defaults.put(WebSearchTool.SETTINGS, new WebSearchTool.Settings("", ""));
        defaults.put(ExtensionScriptTool.SETTINGS, new ExtensionScriptTool.Settings());
        defaults.put(ImageCaptionTool.SETTINGS, new ImageCaptionTool.Settings());
        defaults.put(FileWriteTool.SETTINGS, new FileWriteTool.Settings());
        defaults.put(FileEditTool.SETTINGS, new FileEditTool.Settings());
        defaults.put(FileDeleteTool.SETTINGS, new FileDeleteTool.Settings());
        defaults.put(FileDownloadTool.SETTINGS, new FileDownloadTool.Settings());
        defaults.put(WebReaderTool.SETTINGS, new WebReaderTool.Settings());
        return defaults;
    }

    @Bean(CommandTool.SETTINGS)
    public CommandTool.Settings commandToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(CommandTool.SETTINGS);
        if (data == null) {
            return new CommandTool.Settings(CommandTool.AUTO);
        }
        return objectMapper.convertValue(data, CommandTool.Settings.class);
    }

    @Bean(ExtensionScriptTool.SETTINGS)
    public ExtensionScriptTool.Settings extensionScriptToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(ExtensionScriptTool.SETTINGS);
        if (data == null) {
            return new ExtensionScriptTool.Settings(30L, 65536L);
        }
        return objectMapper.convertValue(data, ExtensionScriptTool.Settings.class);
    }

    @Bean(WebSearchTool.SETTINGS)
    public WebSearchTool.Settings webSearchToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(WebSearchTool.SETTINGS);
        if (data == null) {
            return new WebSearchTool.Settings("", "");
        }
        return objectMapper.convertValue(data, WebSearchTool.Settings.class);
    }

    @Bean(FileWriteTool.SETTINGS)
    public FileWriteTool.Settings fileWriteToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileWriteTool.SETTINGS);
        if (data == null) {
            return new FileWriteTool.Settings(FileWriteTool.AUTO);
        }
        return objectMapper.convertValue(data, FileWriteTool.Settings.class);
    }

    @Bean(FileEditTool.SETTINGS)
    public FileEditTool.Settings fileEditToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileEditTool.SETTINGS);
        if (data == null) {
            return new FileEditTool.Settings(FileEditTool.AUTO);
        }
        return objectMapper.convertValue(data, FileEditTool.Settings.class);
    }

    @Bean(FileDeleteTool.SETTINGS)
    public FileDeleteTool.Settings fileDeleteToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileDeleteTool.SETTINGS);
        if (data == null) {
            return new FileDeleteTool.Settings(FileDeleteTool.AUTO);
        }
        return objectMapper.convertValue(data, FileDeleteTool.Settings.class);
    }

    @Bean(FileDownloadTool.SETTINGS)
    public FileDownloadTool.Settings fileDownloadToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileDownloadTool.SETTINGS);
        if (data == null) {
            return new FileDownloadTool.Settings(FileDownloadTool.ALWAYS_ASKED);
        }
        return objectMapper.convertValue(data, FileDownloadTool.Settings.class);
    }

    @Bean(ImageCaptionTool.SETTINGS)
    public ImageCaptionTool.Settings imageCaptionToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(ImageCaptionTool.SETTINGS);
        if (data == null) {
            return new ImageCaptionTool.Settings();
        }
        return objectMapper.convertValue(data, ImageCaptionTool.Settings.class);
    }

    @Bean(WebReaderTool.SETTINGS)
    public WebReaderTool.Settings webReaderToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(WebReaderTool.SETTINGS);
        if (data == null) {
            return new WebReaderTool.Settings();
        }
        return objectMapper.convertValue(data, WebReaderTool.Settings.class);
    }
}
