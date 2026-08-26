package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色词条服务实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.dto.GlossaryImportResult;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.repository.CharacterGlossaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CharacterGlossaryServiceImplement implements CharacterGlossaryService {

    private static final Logger log = LoggerFactory.getLogger(CharacterGlossaryServiceImplement.class);

    /** keyword 最大长度 */
    private static final int MAX_KEYWORD_LENGTH = 200;

    private final CharacterGlossaryRepository glossaryRepository;

    @Autowired
    public CharacterGlossaryServiceImplement(CharacterGlossaryRepository glossaryRepository) {
        this.glossaryRepository = glossaryRepository;
    }

    @Override
    public List<CharacterGlossary> listByCharacterId(String characterId) {
        if (!StringUtils.hasText(characterId)) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        return glossaryRepository.selectByCharacterId(characterId);
    }

    @Override
    public CharacterGlossary getById(Long id) {
        if (id == null) {
            throw new RuntimeException("词条 ID 不能为空");
        }
        return glossaryRepository.selectById(id);
    }

    @Override
    public CharacterGlossary getByCharacterIdAndKeyword(String characterId, String keyword) {
        if (!StringUtils.hasText(characterId)) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(keyword)) {
            throw new RuntimeException("关键词不能为空");
        }
        return glossaryRepository.selectByCharacterIdAndKeyword(characterId, keyword.trim());
    }

    @Override
    public CharacterGlossary create(CharacterGlossary glossary) {
        if (!StringUtils.hasText(glossary.getCharacterId())) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(glossary.getKeyword())) {
            throw new RuntimeException("关键词不能为空");
        }
        if (glossary.getKeyword().length() > MAX_KEYWORD_LENGTH) {
            throw new RuntimeException("关键词不能超过" + MAX_KEYWORD_LENGTH + "个字符");
        }
        if (!StringUtils.hasText(glossary.getContent())) {
            throw new RuntimeException("词条内容不能为空");
        }

        glossary.setKeyword(glossary.getKeyword().trim());
        glossary.setCreateTime(LocalDateTime.now());
        glossary.setUpdateTime(LocalDateTime.now());

        // 检查同一角色下关键词是否重复
        CharacterGlossary existing = glossaryRepository.selectByCharacterIdAndKeyword(
                glossary.getCharacterId(), glossary.getKeyword());
        if (existing != null) {
            throw new RuntimeException("该角色下已存在相同关键词的词条");
        }

        return glossaryRepository.insert(glossary);
    }

    @Override
    public CharacterGlossary update(CharacterGlossary glossary) {
        if (glossary.getId() == null) {
            throw new RuntimeException("词条 ID 不能为空");
        }

        CharacterGlossary existing = glossaryRepository.selectById(glossary.getId());
        if (existing == null) {
            throw new RuntimeException("词条不存在");
        }

        if (!StringUtils.hasText(glossary.getKeyword())) {
            throw new RuntimeException("关键词不能为空");
        }
        if (glossary.getKeyword().length() > MAX_KEYWORD_LENGTH) {
            throw new RuntimeException("关键词不能超过" + MAX_KEYWORD_LENGTH + "个字符");
        }
        if (!StringUtils.hasText(glossary.getContent())) {
            throw new RuntimeException("词条内容不能为空");
        }

        // 检查关键词唯一性（排除自身）
        String trimmedKeyword = glossary.getKeyword().trim();
        CharacterGlossary duplicate = glossaryRepository.selectByCharacterIdAndKeyword(
                existing.getCharacterId(), trimmedKeyword);
        if (duplicate != null && !duplicate.getId().equals(glossary.getId())) {
            throw new RuntimeException("该角色下已存在相同关键词的词条");
        }

        existing.setKeyword(trimmedKeyword);
        existing.setDesc(glossary.getDesc());
        existing.setContent(glossary.getContent());
        existing.setUpdateTime(LocalDateTime.now());

        return glossaryRepository.update(existing);
    }

    @Override
    public void deleteById(Long id) {
        glossaryRepository.deleteById(id);
    }

    @Override
    public GlossaryImportResult importByCharacterId(String characterId, List<CharacterGlossary> items) {
        if (!StringUtils.hasText(characterId)) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("导入数据不能为空");
        }

        int created = 0, updated = 0, failed = 0;
        for (CharacterGlossary item : items) {
            try {
                if (item == null) {
                    failed++;
                    continue;
                }
                String keyword = item.getKeyword() == null ? "" : item.getKeyword().trim();
                if (keyword.isEmpty() || keyword.length() > MAX_KEYWORD_LENGTH) {
                    failed++;
                    continue;
                }
                String content = item.getContent() == null ? "" : item.getContent().trim();
                if (content.isEmpty()) {
                    failed++;
                    continue;
                }
                String desc = item.getDesc() != null ? item.getDesc().trim() : "";

                // 关键词重复的条目覆盖更新，否则新增
                CharacterGlossary existing = glossaryRepository.selectByCharacterIdAndKeyword(characterId, keyword);
                if (existing != null) {
                    existing.setDesc(desc);
                    existing.setContent(content);
                    existing.setUpdateTime(LocalDateTime.now());
                    glossaryRepository.update(existing);
                    updated++;
                } else {
                    CharacterGlossary glossary = new CharacterGlossary()
                            .setCharacterId(characterId)
                            .setKeyword(keyword)
                            .setDesc(desc)
                            .setContent(content)
                            .setCreateTime(LocalDateTime.now())
                            .setUpdateTime(LocalDateTime.now());
                    glossaryRepository.insert(glossary);
                    created++;
                }
            } catch (Exception e) {
                log.warn("导入单条词条失败: {}", e.getMessage());
                failed++;
            }
        }
        return new GlossaryImportResult()
                .setTotal(items.size())
                .setCreated(created)
                .setUpdated(updated)
                .setFailed(failed);
    }
}
