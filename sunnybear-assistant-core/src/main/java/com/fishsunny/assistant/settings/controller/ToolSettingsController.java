package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.tool.instance.file.FileDeleteTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileDownloadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileEditTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileWriteTool;
import com.fishsunny.assistant.engine.tool.instance.image.ImageCaptionTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebReaderTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebSearchTool;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具设置控制器
 * <p>
 * 提供各内置工具（命令、扩展脚本、联网搜索、文件读写删下载、图片描述、网页阅读）的查询与保存接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class ToolSettingsController {

    private static final Logger log = LoggerFactory.getLogger(ToolSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String toolSettingsPath;
    private final Map<String, Object> toolSettingsMap;

    public ToolSettingsController(
            ObjectMapper objectMapper,
            @Value("${tool-settings.path:settings/tool_settings.json}") String toolSettingsPath,
            @Qualifier(CommandTool.SETTINGS) CommandTool.Settings commandToolSettings,
            @Qualifier(ExtensionScriptTool.SETTINGS) ExtensionScriptTool.Settings extensionScriptToolSettings,
            @Qualifier(WebSearchTool.SETTINGS) WebSearchTool.Settings webSearchToolSettings,
            @Qualifier(FileWriteTool.SETTINGS) FileWriteTool.Settings fileWriteToolSettings,
            @Qualifier(FileEditTool.SETTINGS) FileEditTool.Settings fileEditToolSettings,
            @Qualifier(FileDeleteTool.SETTINGS) FileDeleteTool.Settings fileDeleteToolSettings,
            @Qualifier(FileDownloadTool.SETTINGS) FileDownloadTool.Settings fileDownloadToolSettings,
            @Qualifier(ImageCaptionTool.SETTINGS) ImageCaptionTool.Settings imageCaptionToolSettings,
            @Qualifier(WebReaderTool.SETTINGS) WebReaderTool.Settings webReaderToolSettings) {
        this.objectMapper = objectMapper;
        this.toolSettingsPath = toolSettingsPath;
        this.toolSettingsMap = new LinkedHashMap<>();
        this.toolSettingsMap.put(CommandTool.SETTINGS, commandToolSettings);
        this.toolSettingsMap.put(ExtensionScriptTool.SETTINGS, extensionScriptToolSettings);
        this.toolSettingsMap.put(WebSearchTool.SETTINGS, webSearchToolSettings);
        this.toolSettingsMap.put(FileWriteTool.SETTINGS, fileWriteToolSettings);
        this.toolSettingsMap.put(FileEditTool.SETTINGS, fileEditToolSettings);
        this.toolSettingsMap.put(FileDeleteTool.SETTINGS, fileDeleteToolSettings);
        this.toolSettingsMap.put(FileDownloadTool.SETTINGS, fileDownloadToolSettings);
        this.toolSettingsMap.put(ImageCaptionTool.SETTINGS, imageCaptionToolSettings);
        this.toolSettingsMap.put(WebReaderTool.SETTINGS, webReaderToolSettings);
    }

    @RequestMapping("/command/get")
    public RestResponse getCommandToolSettings() {
        CommandTool.Settings commandToolSettings = (CommandTool.Settings) toolSettingsMap.get(CommandTool.SETTINGS);
        return new RestResponse().success(commandToolSettings);
    }

    @RequestMapping("/extensionscript/get")
    public RestResponse getExtensionScriptToolSettings() {
        ExtensionScriptTool.Settings extensionScriptToolSettings = (ExtensionScriptTool.Settings) toolSettingsMap.get(ExtensionScriptTool.SETTINGS);
        return new RestResponse().success(extensionScriptToolSettings);
    }

    @RequestMapping("/websearch/get")
    public RestResponse getWebSearchToolSettings() {
        WebSearchTool.Settings webSearchToolSettings = (WebSearchTool.Settings) toolSettingsMap.get(WebSearchTool.SETTINGS);
        return new RestResponse().success(webSearchToolSettings);
    }

    @RequestMapping("/filewrite/get")
    public RestResponse getFileWriteToolSettings() {
        FileWriteTool.Settings fileWriteToolSettings = (FileWriteTool.Settings) toolSettingsMap.get(FileWriteTool.SETTINGS);
        return new RestResponse().success(fileWriteToolSettings);
    }

    @RequestMapping("/fileedit/get")
    public RestResponse getFileEditToolSettings() {
        FileEditTool.Settings fileEditToolSettings = (FileEditTool.Settings) toolSettingsMap.get(FileEditTool.SETTINGS);
        return new RestResponse().success(fileEditToolSettings);
    }

    @RequestMapping("/filedelete/get")
    public RestResponse getFileDeleteToolSettings() {
        FileDeleteTool.Settings fileDeleteToolSettings = (FileDeleteTool.Settings) toolSettingsMap.get(FileDeleteTool.SETTINGS);
        return new RestResponse().success(fileDeleteToolSettings);
    }

    @RequestMapping("/filedownload/get")
    public RestResponse getFileDownloadToolSettings() {
        FileDownloadTool.Settings fileDownloadToolSettings = (FileDownloadTool.Settings) toolSettingsMap.get(FileDownloadTool.SETTINGS);
        return new RestResponse().success(fileDownloadToolSettings);
    }

    @RequestMapping("/imagecaption/get")
    public RestResponse getImageCaptionToolSettings() {
        ImageCaptionTool.Settings imageCaptionToolSettings = (ImageCaptionTool.Settings) toolSettingsMap.get(ImageCaptionTool.SETTINGS);
        return new RestResponse().success(imageCaptionToolSettings);
    }

    @RequestMapping("/webreadertool/get")
    public RestResponse getWebReaderToolSettings() {
        WebReaderTool.Settings webReaderToolSettings = (WebReaderTool.Settings) toolSettingsMap.get(WebReaderTool.SETTINGS);
        return new RestResponse().success(webReaderToolSettings);
    }

    @PostMapping("/command/save")
    public RestResponse saveCommandToolSettings(@RequestBody(required = false) CommandTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(CommandTool.AUTO) &&
                !mode.equals(CommandTool.ALWAYS_ASKED) &&
                !mode.equals(CommandTool.ALWAYS_REJECT_DANGER) &&
                !mode.equals(CommandTool.NEVER_ASKED)) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getBlackList() == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getWhiteList() == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getTimeout() == null || settings.getTimeout() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getSafetyOutputSize() == null || settings.getSafetyOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxOutputSize() == null || settings.getMaxOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        CommandTool.Settings commandToolSettings = (CommandTool.Settings) toolSettingsMap.get(CommandTool.SETTINGS);
        commandToolSettings.setMode(settings.getMode())
                .setBlackList(settings.getBlackList())
                .setWhiteList(settings.getWhiteList())
                .setTimeout(settings.getTimeout())
                .setSafetyOutputSize(settings.getSafetyOutputSize())
                .setMaxOutputSize(settings.getMaxOutputSize());
        toolSettingsMap.put(CommandTool.SETTINGS, commandToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 CommandTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/extensionscript/save")
    public RestResponse saveExtensionScriptToolSettings(@RequestBody(required = false) ExtensionScriptTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getTimeout() == null || settings.getTimeout() < 1) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxOutputSize() == null || settings.getMaxOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        ExtensionScriptTool.Settings extSettings = (ExtensionScriptTool.Settings) toolSettingsMap.get(ExtensionScriptTool.SETTINGS);
        extSettings.setTimeout(settings.getTimeout())
                .setMaxOutputSize(settings.getMaxOutputSize());
        toolSettingsMap.put(ExtensionScriptTool.SETTINGS, extSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 ExtensionScriptTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/websearch/save")
    public RestResponse saveWebSearchToolSettings(@RequestBody(required = false) WebSearchTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        WebSearchTool.Settings webSearchToolSettings = (WebSearchTool.Settings) toolSettingsMap.get(WebSearchTool.SETTINGS);
        if (StringUtils.hasText(settings.getMetasoApiKey())) {
            webSearchToolSettings.setMetasoApiKey(settings.getMetasoApiKey());
        }
        if (StringUtils.hasText(settings.getSerperApiKey())) {
            webSearchToolSettings.setSerperApiKey(settings.getSerperApiKey());
        }
        if (!StringUtils.hasText(webSearchToolSettings.getMetasoApiKey())
                && !StringUtils.hasText(webSearchToolSettings.getSerperApiKey())) {
            return new RestResponse().error("至少需要配置一个搜索引擎的 API Key");
        }
        toolSettingsMap.put(WebSearchTool.SETTINGS, webSearchToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 WebSearchTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/filewrite/save")
    public RestResponse saveFileWriteToolSettings(@RequestBody(required = false) FileWriteTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileWriteTool.AUTO) &&
                !mode.equals(FileWriteTool.ALWAYS_ASKED) &&
                !mode.equals(FileWriteTool.NEVER_ASKED) &&
                !mode.equals(FileWriteTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileWriteTool.Settings fileWriteToolSettings = (FileWriteTool.Settings) toolSettingsMap.get(FileWriteTool.SETTINGS);
        fileWriteToolSettings.setMode(mode);
        toolSettingsMap.put(FileWriteTool.SETTINGS, fileWriteToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileWriteTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/fileedit/save")
    public RestResponse saveFileEditToolSettings(@RequestBody(required = false) FileEditTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileEditTool.AUTO) &&
                !mode.equals(FileEditTool.ALWAYS_ASKED) &&
                !mode.equals(FileEditTool.NEVER_ASKED) &&
                !mode.equals(FileEditTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileEditTool.Settings fileEditToolSettings = (FileEditTool.Settings) toolSettingsMap.get(FileEditTool.SETTINGS);
        fileEditToolSettings.setMode(mode);
        toolSettingsMap.put(FileEditTool.SETTINGS, fileEditToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileEditTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/filedelete/save")
    public RestResponse saveFileDeleteToolSettings(@RequestBody(required = false) FileDeleteTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileDeleteTool.AUTO) &&
                !mode.equals(FileDeleteTool.ALWAYS_ASKED) &&
                !mode.equals(FileDeleteTool.NEVER_ASKED) &&
                !mode.equals(FileDeleteTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileDeleteTool.Settings fileDeleteToolSettings = (FileDeleteTool.Settings) toolSettingsMap.get(FileDeleteTool.SETTINGS);
        fileDeleteToolSettings.setMode(mode);
        toolSettingsMap.put(FileDeleteTool.SETTINGS, fileDeleteToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileDeleteTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/filedownload/save")
    public RestResponse saveFileDownloadToolSettings(@RequestBody(required = false) FileDownloadTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileDownloadTool.ALWAYS_ASKED) &&
                !mode.equals(FileDownloadTool.NEVER_ASKED)) {
            return new RestResponse().error("Invalid settings");
        }
        FileDownloadTool.Settings fileDownloadToolSettings = (FileDownloadTool.Settings) toolSettingsMap.get(FileDownloadTool.SETTINGS);
        fileDownloadToolSettings.setMode(mode);
        toolSettingsMap.put(FileDownloadTool.SETTINGS, fileDownloadToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/imagecaption/save")
    public RestResponse saveImageCaptionToolSettings(@RequestBody(required = false) ImageCaptionTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxLength() == null || settings.getMaxLength() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        ImageCaptionTool.Settings captionTool = (ImageCaptionTool.Settings) toolSettingsMap.get(ImageCaptionTool.SETTINGS);
        captionTool.setMaxLength(settings.getMaxLength());
        toolSettingsMap.put(ImageCaptionTool.SETTINGS, captionTool);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/webreadertool/save")
    public RestResponse saveWebReaderToolSettings(@RequestBody(required = false) WebReaderTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getBrowserTimeoutMs() == null || settings.getBrowserTimeoutMs() < 1000) {
            return new RestResponse().error("浏览器超时至少为 1000ms");
        }
        WebReaderTool.Settings webReaderToolSettings = (WebReaderTool.Settings) toolSettingsMap.get(WebReaderTool.SETTINGS);
        webReaderToolSettings.setBrowserTimeoutMs(settings.getBrowserTimeoutMs());
        toolSettingsMap.put(WebReaderTool.SETTINGS, webReaderToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
}
