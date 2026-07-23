package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色词条数据访问实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class CharacterGlossaryRepositoryImplement implements CharacterGlossaryRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CharacterGlossaryRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<CharacterGlossary> rowMapper = (resultSet, i) -> {
        CharacterGlossary glossary = new CharacterGlossary();
        glossary.setId(resultSet.getLong("id"));
        glossary.setCharacterId(resultSet.getString("character_id"));
        glossary.setKeyword(resultSet.getString("keyword"));
        glossary.setDesc(resultSet.getString("desc"));
        glossary.setContent(resultSet.getString("content"));
        glossary.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        glossary.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return glossary;
    };

    @Override
    public CharacterGlossary insert(CharacterGlossary glossary) {
        String sql =
                """
                INSERT INTO character_glossary
                (character_id, keyword, desc, content, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                glossary.getCharacterId(),
                glossary.getKeyword(),
                glossary.getDesc() != null ? glossary.getDesc() : "",
                glossary.getContent() != null ? glossary.getContent() : "",
                glossary.getCreateTime().format(formatter),
                glossary.getUpdateTime().format(formatter)
        );

        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        return selectById(id);
    }

    @Override
    public CharacterGlossary update(CharacterGlossary glossary) {
        String sql =
                """
                UPDATE character_glossary
                SET keyword = ?, desc = ?, content = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                glossary.getKeyword(),
                glossary.getDesc() != null ? glossary.getDesc() : "",
                glossary.getContent() != null ? glossary.getContent() : "",
                glossary.getUpdateTime().format(formatter),
                glossary.getId()
        );

        return selectById(glossary.getId());
    }

    @Override
    public CharacterGlossary deleteById(Long id) {
        CharacterGlossary glossary = selectById(id);
        if (glossary == null) {
            throw new RuntimeException("词条不存在");
        }
        String sql = "DELETE FROM character_glossary WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return glossary;
    }

    @Override
    public CharacterGlossary selectById(Long id) {
        String sql = "SELECT * FROM character_glossary WHERE id = ?";
        List<CharacterGlossary> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<CharacterGlossary> selectByCharacterId(String characterId) {
        String sql = "SELECT * FROM character_glossary WHERE character_id = ? ORDER BY keyword ASC";
        return jdbcTemplate.query(sql, rowMapper, characterId);
    }

    @Override
    public CharacterGlossary selectByCharacterIdAndKeyword(String characterId, String keyword) {
        String sql = "SELECT * FROM character_glossary WHERE character_id = ? AND keyword = ?";
        List<CharacterGlossary> list = jdbcTemplate.query(sql, rowMapper, characterId, keyword);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public void deleteByCharacterId(String characterId) {
        String sql = "DELETE FROM character_glossary WHERE character_id = ?";
        jdbcTemplate.update(sql, characterId);
    }
}
