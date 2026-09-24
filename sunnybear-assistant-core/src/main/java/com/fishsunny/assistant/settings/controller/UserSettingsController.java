package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.settings.UserSettings;
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
 * 用户设置控制器
 * <p>
 * 提供用户基本设置、头像与背景的查询、保存与文件上传接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class UserSettingsController {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String fileBasePath;
    private final String userSettingsPath;
    private final UserSettings userSettings;

    public UserSettingsController(
            ObjectMapper objectMapper,
            @Value("${assistant.file.base-path:data/}") String fileBasePath,
            @Value("${user-settings.path:settings/user_settings.json}") String userSettingsPath,
            UserSettings userSettings) {
        this.objectMapper = objectMapper;
        this.fileBasePath = fileBasePath;
        this.userSettingsPath = userSettingsPath;
        this.userSettings = userSettings;
    }

    @RequestMapping("/user/get")
    public RestResponse getUserSettings() {
        return new RestResponse().success(userSettings);
    }

    @PostMapping("/user/save")
    public RestResponse saveUserSettings(@RequestBody(required = false) UserSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getUsername())) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getOpacity() == null) {
            settings.setOpacity(0.3);
        }
        if (!StringUtils.hasText(settings.getMainColor())) {
            settings.setMainColor("lightsalmon");
        }
        userSettings.setUsername(settings.getUsername());
        userSettings.setOpacity(settings.getOpacity());
        userSettings.setMainColor(settings.getMainColor());
        userSettings.setEnableAutoSwitchModel(
                Boolean.TRUE.equals(settings.getEnableAutoSwitchModel()));
        userSettings.setProModelThreshold(settings.getProModelThreshold());
        userSettings.setContextTokenLimit(settings.getContextTokenLimit());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    // ==================== 用户头像上传 / 删除 ====================

    @PostMapping("/user/avatar/upload")
    public RestResponse uploadUserAvatar(@RequestParam("file") MultipartFile file) {
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
            String filename = "avatar" + ext;
            Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            userSettings.setAvatar(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            log.info("用户头像已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传用户头像失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/user/avatar/delete")
    public RestResponse deleteUserAvatar() {
        try {
            String oldPath = userSettings.getAvatar();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除用户头像文件失败: {}", oldPath);
                    throw new RuntimeException("删除用户头像文件失败");
                }
            }
            userSettings.setAvatar("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除用户头像失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }

    // ==================== 用户背景上传 / 删除 ====================

    @PostMapping("/user/background/upload")
    public RestResponse uploadUserBackground(@RequestParam("file") MultipartFile file) {
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
            byte[] scaled = new MultipartScaleImageHelper(file).scaleImage(1920);
            String filename = "background" + ext;
            Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            userSettings.setBackground(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            log.info("用户背景已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传用户背景失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/user/background/delete")
    public RestResponse deleteUserBackground() {
        try {
            String oldPath = userSettings.getBackground();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除用户背景文件失败: {}", oldPath);
                }
            }
            userSettings.setBackground("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除用户背景失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }
}
