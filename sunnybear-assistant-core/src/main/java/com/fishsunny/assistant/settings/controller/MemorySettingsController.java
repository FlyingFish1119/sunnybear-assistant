package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.settings.MemorySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;

/**
 * 记忆设置控制器
 * <p>
 * 提供记忆注入开关的查询与保存接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class MemorySettingsController {

    private static final Logger log = LoggerFactory.getLogger(MemorySettingsController.class);

    private final ObjectMapper objectMapper;
    private final String memorySettingsPath;
    private final MemorySettings memorySettings;

    public MemorySettingsController(
            ObjectMapper objectMapper,
            @Value("${memory-settings.path:data/settings/memory_settings.json}") String memorySettingsPath,
            MemorySettings memorySettings) {
        this.objectMapper = objectMapper;
        this.memorySettingsPath = memorySettingsPath;
        this.memorySettings = memorySettings;
    }

    // ==================== 记忆设置 ====================

    @RequestMapping("/memorysettings/get")
    public RestResponse getMemorySettings() {
        return new RestResponse().success(memorySettings);
    }

    @PostMapping("/memorysettings/save")
    public RestResponse saveMemorySettings(@RequestBody(required = false) MemorySettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        Integer idleHours = settings.getAutoRecordIdleHours();
        // 整体写回单例 bean：三个字段一次到位，避免某次只传了一个字段把其它字段清掉
        memorySettings.setEnable(Boolean.TRUE.equals(settings.getEnable()))
                .setEnableAutoRecord(Boolean.TRUE.equals(settings.getEnableAutoRecord()))
                .setAutoRecordIdleHours(idleHours == null || idleHours <= 0 ? 24 : idleHours);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(memorySettingsPath), memorySettings);
        } catch (Exception e) {
            log.error("保存记忆设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
}
