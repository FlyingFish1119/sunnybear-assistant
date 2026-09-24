package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 助手设置控制器
 * <p>
 * 提供助手基本设置与头像的查询、保存与文件上传接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class AssistantSettingsController {

    private static final Logger log = LoggerFactory.getLogger(AssistantSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String fileBasePath;
    private final String assistantSettingsPath;
    private final AssistantSettings assistantSettings;

    public AssistantSettingsController(
            ObjectMapper objectMapper,
            @Value("${assistant.file.base-path:data/}") String fileBasePath,
            @Value("${assistant-settings.path:settings/assistant_settings.json}") String assistantSettingsPath,
            AssistantSettings assistantSettings) {
        this.objectMapper = objectMapper;
        this.fileBasePath = fileBasePath;
        this.assistantSettingsPath = assistantSettingsPath;
        this.assistantSettings = assistantSettings;
    }

    @RequestMapping("/assistant/get")
    public RestResponse getAssistantSettings() {
        return new RestResponse().success(assistantSettings);
    }

    @PostMapping("/assistant/save")
    public RestResponse saveAssistantSettings(@RequestBody(required = false) AssistantSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getAssistantName())) {
            return new RestResponse().error("Invalid settings");
        }
        assistantSettings.setAssistantName(settings.getAssistantName());
        assistantSettings.setPrompt(settings.getPrompt());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    // ==================== 助手头像上传 / 删除 ====================

    @PostMapping("/assistant/avatar/upload")
    public RestResponse uploadAssistantAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !ScaleImageHelper.checkIsImage(originalFilename)) {
            return new RestResponse().error("不支持的图片格式");
        }
        try {
            String userDir = fileBasePath + "user/";
            if (!new File(userDir).mkdirs()) {
                log.warn("创建用户目录失败: {}", userDir);
            }
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            byte[] scaled = new MultipartScaleImageHelper(file).scaleImage(256);
            String filename = "assistant_avatar" + ext;
            Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            assistantSettings.setAvatar(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
            log.info("助手头像已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传助手头像失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/assistant/avatar/delete")
    public RestResponse deleteAssistantAvatar() {
        try {
            String oldPath = assistantSettings.getAvatar();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除助手头像文件失败: {}", oldPath);
                    throw new RuntimeException("删除助手头像文件失败");
                }
            }
            assistantSettings.setAvatar("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除助手头像失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }
}
