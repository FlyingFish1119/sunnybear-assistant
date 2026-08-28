package com.fishsunny.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;

@EnableScheduling
@SpringBootApplication
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        // 创建数据目录
        InputStream applicationInput = App.class.getClassLoader().getResourceAsStream("application.yml");
        if (applicationInput != null) {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(applicationInput);
            initDataDirectory(root);
            initExtensionDirectory(root);
            initSettingsDirectory(root);
        }
        SpringApplication.run(App.class, args);
    }

    private static void initDataDirectory(JsonNode root) {
        String basePath = root.path("assistant")
                .path("file")
                .path("base-path")
                .asText("data/");
        if (StringUtils.hasText(basePath)) {
            File dir = new File(basePath);
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("Failed to create data directory: {}", dir.getAbsolutePath());
            } else {
                log.info("Created data directory: {}", dir.getAbsolutePath());
            }
        }
    }

    private static void initExtensionDirectory(JsonNode root) throws Exception {
        StringBuilder readmeContent = new StringBuilder();
        try (InputStream readmeInputStream = App.class.getClassLoader().getResourceAsStream("TOOL_EXTENSION_README.md")) {
            if (readmeInputStream == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(readmeInputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                readmeContent.append(line).append("\n");
            }
        }

        String extensionPath = root.path("engine")
                .path("tool")
                .path("extension")
                .path("dir")
                .asText("tool-extension/");
        if (StringUtils.hasText(extensionPath)) {
            File dir = new File(extensionPath);
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("Failed to create extension directory: {}", dir.getAbsolutePath());
            } else {
                log.info("Created extension directory: {}", dir.getAbsolutePath());
            }
            File readmeFile = new File(dir, "TOOL_EXTENSION_README.md");
            Files.write(readmeFile.toPath(), readmeContent.toString().getBytes());
            log.info("Created extension TOOL_EXTENSION_README.md: {}", readmeFile.getAbsolutePath());
        }
    }

    private static void initSettingsDirectory(JsonNode root) {
        String basePath = root.path("assistant")
                .path("settings")
                .path("base-path")
                .asText("settings/");
        if (StringUtils.hasText(basePath)) {
            File dir = new File(basePath);
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("Failed to create settings directory: {}", dir.getAbsolutePath());
            } else {
                log.info("Created settings directory: {}", dir.getAbsolutePath());
            }
        }
    }
}
