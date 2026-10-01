package com.fishsunny.assistant.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 在 Spring 容器 refresh、任何 Bean 初始化之前，基于已加载且可解析占位符的 Environment
 * 创建数据/设置/扩展目录。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/10/2
 */
public class DirInitEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public DirInitEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        initDirectory(environment.getProperty("assistant.file.base-path", "data/"), "data");
        initDirectory(environment.getProperty("assistant.settings.base-path", "settings/"), "settings");
        initExtensionDirectory(environment.getProperty("engine.tool.extension.dir", "tool-extension/"));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void initDirectory(String basePath, String label) {
        if (!StringUtils.hasText(basePath)) {
            return;
        }
        File dir = new File(basePath);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Failed to create " + label + " directory: " + dir.getAbsolutePath());
        } else {
            log.info("Created " + label + " directory: " + dir.getAbsolutePath());
        }
    }

    private void initExtensionDirectory(String extensionPath) {
        if (!StringUtils.hasText(extensionPath)) {
            return;
        }
        File dir = new File(extensionPath);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Failed to create extension directory: " + dir.getAbsolutePath());
            return;
        }
        log.info("Created extension directory: " + dir.getAbsolutePath());

        try (InputStream readmeInputStream = getClass().getClassLoader().getResourceAsStream("TOOL_EXTENSION_README.md")) {
            if (readmeInputStream == null) {
                return;
            }
            StringBuilder readmeContent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(readmeInputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    readmeContent.append(line).append("\n");
                }
            }
            File readmeFile = new File(dir, "TOOL_EXTENSION_README.md");
            Files.write(readmeFile.toPath(), readmeContent.toString().getBytes(StandardCharsets.UTF_8));
            log.info("Created extension TOOL_EXTENSION_README.md: " + readmeFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write TOOL_EXTENSION_README.md: " + e.getMessage());
        }
    }
}
