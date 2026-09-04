package com.fishsunny.assistant.plug.world;

/*
 * @Usage 世界观插件启动初始化 —— 释放插件 README + 一次性迁移：旧 world_session_mapping 表 → chat_session(type+extension)
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/28 16:59
 */

import com.fishsunny.assistant.App;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import com.fishsunny.assistant.plug.world.service.WorldSessionBindings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;

    /** 注入仅为建立初始化顺序：repository 构造完成后 chat_session 的 extension 列才存在 */
    @SuppressWarnings("unused")
    private final ChatSessionRepository chatSessionRepository;

    @Autowired
    public WorldOnStart(JdbcTemplate jdbcTemplate, ChatSessionRepository chatSessionRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        writePlugReadme();
        migrateLegacyMappingTable();
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

    /**
     * 一次性迁移：world_session_mapping → chat_session(type='world', extension)，随后删表。
     * 全程 try-catch：迁移完成（表已删）后自动空跑；UPDATE 成功但 DROP 失败时下次重复执行同值 UPDATE，幂等无害。
     */
    private void migrateLegacyMappingTable() {
        try {
            int migrated = jdbcTemplate.update("""
                    UPDATE chat_session
                    SET type = '%s',
                        extension = '{"%s":"' || m.world_id || '"}'
                    FROM world_session_mapping m
                    WHERE chat_session.id = m.session_id
                    """.formatted(WorldSessionBindings.SESSION_TYPE, WorldSessionBindings.EXTENSION_KEY));
            if (migrated > 0) {
                log.info("Migration: {} 条绑定自 world_session_mapping 迁移至 chat_session.extension", migrated);
            }
        } catch (Exception e) {
            log.debug("Migration: world_session_mapping 不存在或已迁移，跳过. {}", e.getMessage());
            return;
        }
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS world_session_mapping");
            log.info("Migration: world_session_mapping 表已删除");
        } catch (Exception e) {
            log.debug("Migration: 删除 world_session_mapping 跳过. {}", e.getMessage());
        }
    }
}
