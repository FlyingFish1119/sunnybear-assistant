package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.UserSettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * 用户设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 user_settings.json 文件，
 * 并将其反序列化为 {@link UserSettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class UserSettingsLoader {

    private static final String USER_SETTINGS_JSON = "user_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public UserSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析用户设置文件，装配为 Spring Bean
     * <p>
     * 读取流程：
     * <ol>
     *   <li>根据配置的路径定位 JSON 文件</li>
     *   <li>使用 Jackson 将 JSON 反序列化为 UserSettings 对象</li>
     *   <li>若文件不存在或格式错误，返回带有默认值的 UserSettings 对象</li>
     * </ol>
     *
     * @return 从文件中解析的 UserSettings，或默认的 UserSettings（当读取失败时）
     */
    @Bean
    public UserSettings userSettings() {
        File settingsFile = new File(basePath + "/" + USER_SETTINGS_JSON);

        // 检查路径是否存在
        if (!settingsFile.exists()) {
            log.warn("用户设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new UserSettings();
        }

        // 检查是否为文件
        if (!settingsFile.isFile()) {
            log.warn("用户设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new UserSettings();
        }

        try {
            // 读取并解析 JSON 文件
            UserSettings settings = objectMapper.readValue(settingsFile, UserSettings.class);
            log.info("用户设置文件加载成功: {}，内容: username={}, avatar={}, background={}, opacity={}",
                    settingsFile.getAbsolutePath(),
                    settings.getUsername(),
                    settings.getAvatar(),
                    settings.getBackground(),
                    settings.getOpacity());
            return settings;

        } catch (IOException e) {
            log.error("读取用户设置文件失败: {}，原因: {}，将使用默认设置",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new UserSettings();
        }
    }
}
