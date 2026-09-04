package com.fishsunny.assistant.plug.character;

/*
 * @Usage 角色插件启动初始化 —— 一次性迁移：旧 character_session_mapping 表 → chat_session(type+extension)，随后删表
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/4
 */

import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import com.fishsunny.assistant.plug.character.service.CharacterSessionBindings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CharacterOnStart implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;

    /** 注入仅为建立初始化顺序：repository 构造完成后 chat_session 的 extension 列才存在 */
    @SuppressWarnings("unused")
    private final ChatSessionRepository chatSessionRepository;

    @Autowired
    public CharacterOnStart(JdbcTemplate jdbcTemplate, ChatSessionRepository chatSessionRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Override
    public void afterPropertiesSet() {
        migrateLegacyMappingTable();
    }

    /**
     * 一次性迁移：character_session_mapping → chat_session(type='character', extension)。
     * 全程 try-catch：迁移完成（表已删）后自动空跑；UPDATE 成功但 DROP 失败时下次重复执行同值 UPDATE，幂等无害。
     */
    private void migrateLegacyMappingTable() {
        try {
            int migrated = jdbcTemplate.update("""
                    UPDATE chat_session
                    SET type = '%s',
                        extension = '{"%s":"' || m.character_id || '"}'
                    FROM character_session_mapping m
                    WHERE chat_session.id = m.session_id
                    """.formatted(CharacterSessionBindings.SESSION_TYPE, CharacterSessionBindings.EXTENSION_KEY));
            if (migrated > 0) {
                log.info("Migration: {} 条绑定自 character_session_mapping 迁移至 chat_session.extension", migrated);
            }
        } catch (Exception e) {
            log.debug("Migration: character_session_mapping 不存在或已迁移，跳过. {}", e.getMessage());
            return;
        }
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS character_session_mapping");
            log.info("Migration: character_session_mapping 表已删除");
        } catch (Exception e) {
            log.debug("Migration: 删除 character_session_mapping 跳过. {}", e.getMessage());
        }
    }
}
