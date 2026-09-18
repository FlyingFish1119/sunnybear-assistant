package com.fishsunny.assistant.plug.world;

/*
 * @Usage 世界观插件启动初始化 —— 释放插件 README 到插件目录 + 登记前端页面
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/28 16:59
 */

import com.fishsunny.assistant.App;
import com.fishsunny.assistant.mvc.controller.GlobalRouteController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;

@Slf4j
@Component
public class WorldOnStart implements InitializingBean {

    @Value("${plug.file-path:plugin/}")
    private String filePath;

    @Override
    public void afterPropertiesSet() throws Exception {
        writePlugReadme();
        registerPages();
    }

    /** 登记世界观插件的前端页面到全局导航 */
    private void registerPages() {
        GlobalRouteController.register(
                "群聊", "plug/world/world_index.html", "群聊", "多角色群聊与旁白调度", "messages-square");
        GlobalRouteController.register(
                "群聊", "plug/world/world_settings.html", "世界设置", "管理世界书、知识库与预设", "book-open");
    }

    /** 释放 WORLD_PLUG_README.md 到插件目录 */
    private void writePlugReadme() {
        StringBuilder readmeContent = new StringBuilder();
        try (InputStream readmeInputStream = App.class.getClassLoader().getResourceAsStream("WORLD_PLUG_README.md")) {
            if (readmeInputStream == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(readmeInputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                readmeContent.append(line).append("\n");
            }
        } catch (Exception e) {
            log.error("Failed to read WORLD_PLUG_README.md", e);
            return;
        }
        File baseFile = new File(filePath);
        if (!baseFile.exists() && !baseFile.mkdirs()) {
            log.error("Failed to create directory: {}", filePath);
        }
        try {
            File readmeFile = new File(baseFile, "WORLD_PLUG_README.md");
            Files.write(readmeFile.toPath(), readmeContent.toString().getBytes());
        } catch (Exception e) {
            log.error("Failed to write WORLD_PLUG_README.md", e);
        }
        log.info("WORLD_PLUG_README.md has been written.");
    }
}
