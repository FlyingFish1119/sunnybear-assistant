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

import java.io.File;
import java.io.InputStream;

// 什么叫做模型更新了我项目还要更新SpringAI依赖?你活不活了?

@EnableScheduling
@SpringBootApplication
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        // SQLite 不会自动创建父目录，在 Spring 启动前确保目录存在
        InputStream in = App.class.getClassLoader().getResourceAsStream("application.yml");
        if (in != null) {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            String basePath = root.path("assistant").path("file").path("base-path").asText(null);
            if (StringUtils.hasText(basePath)) {
                File dir = new File(basePath);
                if (!dir.exists() && !dir.mkdirs()) {
                    log.warn("Failed to create data directory: {}", dir.getAbsolutePath());
                } else {
                    log.info("Created data directory: {}", dir.getAbsolutePath());
                }
            }
        }
        SpringApplication.run(App.class, args);
    }
}
