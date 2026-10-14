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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 在 Spring 容器 refresh、任何 Bean 初始化之前，基于已加载且可解析占位符的 Environment
 * 创建数据/设置/扩展目录，并把内置的 application.yml 释放一份到启动目录。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/10/2
 */
public class DirInitEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String CONFIG_NAME = "application.yml";

    private final Log log;

    public DirInitEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        initDirectory(environment.getProperty("assistant.file.base-path", "data/"), "data");
        initDirectory(environment.getProperty("assistant.settings.base-path", "settings/"), "settings");
        initExtensionDirectory(environment.getProperty("engine.tool.extension.dir", "tool-extension/"));
        releaseDefaultConfigIfAbsent();
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
            Files.writeString(readmeFile.toPath(), readmeContent.toString());
            log.info("Created extension TOOL_EXTENSION_README.md: " + readmeFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write TOOL_EXTENSION_README.md: " + e.getMessage());
        }
    }

    /**
     * 把内置的 application.yml 释放一份到启动目录，已存在则跳过，因此用户改过的外置配置不会被后续启动覆盖。
     *
     * <p>本处理器在配置数据加载之后执行，所以释放出来的文件从下一次启动开始被读取；
     * 其内容与内置版本逐字节一致，首次启动的实际行为不受影响。
     */
    private void releaseDefaultConfigIfAbsent() {
        Path target = Paths.get(System.getProperty("user.dir"), CONFIG_NAME);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_NAME)) {
            if (in == null) {
                log.warn("Bundled " + CONFIG_NAME + " not found in classpath, skip releasing");
                return;
            }
            // CREATE_NEW：只在此文件确实不存在时落盘，避免与同时启动的另一个进程互相覆盖
            Files.write(target, in.readAllBytes(), StandardOpenOption.CREATE_NEW);
            log.info("Created default config: " + target.toAbsolutePath());
        } catch (FileAlreadyExistsException e) {
            // 并发启动时对方已经写好了，视作正常
        } catch (IOException e) {
            log.warn("Failed to create default config: " + e.getMessage());
        }
    }
}
