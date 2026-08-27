package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观导入/导出服务实现
 *        导出：ID → 角色名解析、aiSettings JSON 字符串 → 对象、剔除头像/背景图/ID/时间戳
 *        导入：宽容校验（名称必填/去重/知识引用的角色名必须存在）、角色名 → 新 ID 映射、
 *              支持「新建世界观」与「覆盖已有世界观」两种模式，整体事务
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.plug.world.dto.WorldFileCharacter;
import com.fishsunny.assistant.plug.world.dto.WorldFileData;
import com.fishsunny.assistant.plug.world.dto.WorldFileKnowledge;
import com.fishsunny.assistant.plug.world.dto.WorldFileWorld;
import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.plug.world.repository.WorldCharacterRepository;
import com.fishsunny.assistant.plug.world.repository.WorldKnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WorldImportExportServiceImplement implements WorldImportExportService {

    private static final Logger log = LoggerFactory.getLogger(WorldImportExportServiceImplement.class);

    /** 导出文件的类型标识 */
    private static final String FILE_TYPE = "sunnybear-world";
    /** 当前支持的文件格式版本 */
    private static final int FILE_VERSION = 1;
    /** 世界观名称最大长度（与控制器一致） */
    private static final int MAX_NAME_LENGTH = 50;
    /** 知识标题缺省长度（AI 编辑漏写标题时取内容前 N 字） */
    private static final int DEFAULT_TITLE_LENGTH = 15;

    private final WorldInfoService worldInfoService;
    private final WorldCharacterService worldCharacterService;
    private final WorldKnowledgeService worldKnowledgeService;
    private final WorldCharacterRepository worldCharacterRepository;
    private final WorldKnowledgeRepository worldKnowledgeRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public WorldImportExportServiceImplement(WorldInfoService worldInfoService,
                                             WorldCharacterService worldCharacterService,
                                             WorldKnowledgeService worldKnowledgeService,
                                             WorldCharacterRepository worldCharacterRepository,
                                             WorldKnowledgeRepository worldKnowledgeRepository,
                                             ObjectMapper objectMapper) {
        this.worldInfoService = worldInfoService;
        this.worldCharacterService = worldCharacterService;
        this.worldKnowledgeService = worldKnowledgeService;
        this.worldCharacterRepository = worldCharacterRepository;
        this.worldKnowledgeRepository = worldKnowledgeRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 导出 ====================

    @Override
    public WorldFileData exportWorld(String worldId) {
        WorldInfo world = worldInfoService.findById(worldId);
        if (world == null) {
            throw new RuntimeException("世界观不存在");
        }
        List<WorldCharacter> characters = worldCharacterService.findByWorldId(worldId);
        List<WorldKnowledge> knowledgeList = worldKnowledgeService.findByWorldId(worldId);

        WorldFileData data = new WorldFileData();
        data.setVersion(FILE_VERSION);
        data.setType(FILE_TYPE);
        data.setWorld(buildFileWorld(world));
        data.setCharacters(characters.stream().map(this::buildFileCharacter).collect(Collectors.toList()));

        // 知识知晓角色 id → 名称（导出后由 AI 按名称引用）
        Map<String, String> idToName = characters.stream()
                .collect(Collectors.toMap(WorldCharacter::getId, WorldCharacter::getName, (a, b) -> a));
        data.setKnowledge(knowledgeList.stream()
                .map(k -> buildFileKnowledge(k, idToName))
                .collect(Collectors.toList()));
        return data;
    }

    private WorldFileWorld buildFileWorld(WorldInfo world) {
        WorldFileWorld fileWorld = new WorldFileWorld();
        fileWorld.setName(world.getName());
        fileWorld.setDescription(world.getDescription());
        fileWorld.setPreset(world.getPreset());
        fileWorld.setMainColor(world.getMainColor());
        fileWorld.setNarrationEnable(world.getNarrationEnable());
        fileWorld.setPossessName(world.getPossessName());
        fileWorld.setMaxRounds(world.getMaxRounds());
        fileWorld.setSchedulerAiSettings(parseJsonObject(world.getSchedulerAiSettings()));
        return fileWorld;
    }

    private WorldFileCharacter buildFileCharacter(WorldCharacter character) {
        WorldFileCharacter fileCharacter = new WorldFileCharacter();
        fileCharacter.setName(character.getName());
        fileCharacter.setIntro(character.getIntro());
        fileCharacter.setSetting(character.getSetting());
        fileCharacter.setAiSettings(parseJsonObject(character.getAiSettings()));
        return fileCharacter;
    }

    private WorldFileKnowledge buildFileKnowledge(WorldKnowledge knowledge, Map<String, String> idToName) {
        WorldFileKnowledge fileKnowledge = new WorldFileKnowledge();
        fileKnowledge.setTitle(knowledge.getTitle());
        fileKnowledge.setContent(knowledge.getContent());
        List<String> names = new ArrayList<>();
        if (knowledge.getCharacterIds() != null) {
            for (String characterId : knowledge.getCharacterIds()) {
                String name = idToName.get(characterId);
                if (name != null && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        fileKnowledge.setCharacters(names);
        return fileKnowledge;
    }

    /** 解析 JSON 字符串为对象（解析失败返回空对象） */
    private Map<String, Object> parseJsonObject(String json) {
        if (StringUtils.hasText(json)) {
            try {
                JavaType mapType = objectMapper.getTypeFactory().constructType(Map.class);
                return objectMapper.readValue(json, mapType);
            } catch (Exception e) {
                log.warn("解析 AI 配置 JSON 失败: {}", e.getMessage());
            }
        }
        return new HashMap<>();
    }

    // ==================== 导入 ====================

    @Override
    @Transactional
    public WorldInfo importWorld(WorldFileData data, String targetWorldId) {
        if (data == null) {
            throw new RuntimeException("导入数据不能为空");
        }
        if (data.getType() != null && !FILE_TYPE.equals(data.getType())) {
            throw new RuntimeException("不是有效的世界观文件（type 不匹配）");
        }
        if (data.getVersion() != null && data.getVersion() > FILE_VERSION) {
            throw new RuntimeException("文件版本过新，请升级应用后再导入");
        }

        // —— 先全量校验，任何一步失败都不落库 ——
        WorldFileWorld fileWorld = data.getWorld();
        if (fileWorld == null) {
            throw new RuntimeException("导入数据缺少 world 信息");
        }
        if (!StringUtils.hasText(fileWorld.getName())) {
            throw new RuntimeException("世界观名称不能为空");
        }
        if (fileWorld.getName().trim().length() > MAX_NAME_LENGTH) {
            throw new RuntimeException("世界观名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }

        List<WorldFileCharacter> fileCharacters =
                data.getCharacters() == null ? new ArrayList<>() : data.getCharacters();
        Set<String> characterNameSet = new HashSet<>();
        for (int i = 0; i < fileCharacters.size(); i++) {
            WorldFileCharacter fileCharacter = fileCharacters.get(i);
            if (!StringUtils.hasText(fileCharacter.getName())) {
                throw new RuntimeException("第 " + (i + 1) + " 个角色缺少名称");
            }
            if (fileCharacter.getName().trim().length() > MAX_NAME_LENGTH) {
                throw new RuntimeException("第 " + (i + 1) + " 个角色名称超过" + MAX_NAME_LENGTH + "个字符: " + fileCharacter.getName());
            }
            if (!characterNameSet.add(fileCharacter.getName().trim())) {
                throw new RuntimeException("角色名称重复: " + fileCharacter.getName());
            }
        }

        List<WorldFileKnowledge> fileKnowledge =
                data.getKnowledge() == null ? new ArrayList<>() : data.getKnowledge();
        for (int i = 0; i < fileKnowledge.size(); i++) {
            WorldFileKnowledge fileK = fileKnowledge.get(i);
            if (!StringUtils.hasText(fileK.getTitle()) && !StringUtils.hasText(fileK.getContent())) {
                throw new RuntimeException("第 " + (i + 1) + " 条知识标题和内容均为空");
            }
            String title = StringUtils.hasText(fileK.getTitle()) ? fileK.getTitle() : "未命名";
            for (String name : fileK.getCharacters() == null ? Collections.<String>emptyList() : fileK.getCharacters()) {
                if (!characterNameSet.contains(name)) {
                    throw new RuntimeException("知识「" + title + "」引用了不存在的角色: " + name);
                }
            }
        }

        // —— 世界观本体 ——
        WorldInfo world = new WorldInfo();
        world.setName(fileWorld.getName().trim());
        world.setDescription(fileWorld.getDescription() == null ? "" : fileWorld.getDescription());
        world.setPreset(fileWorld.getPreset() == null ? "" : fileWorld.getPreset());
        world.setMainColor(fileWorld.getMainColor() == null ? "" : fileWorld.getMainColor());
        world.setNarrationEnable(fileWorld.getNarrationEnable() == null ? Boolean.TRUE : fileWorld.getNarrationEnable());
        int maxRounds = fileWorld.getMaxRounds() == null ? 5 : fileWorld.getMaxRounds();
        world.setMaxRounds(Math.clamp(maxRounds, 1, 50));
        world.setSchedulerAiSettings(normalizeJson(fileWorld.getSchedulerAiSettings()));
        // 夺舍角色必须存在于导入的角色中，否则清空
        String possessName = fileWorld.getPossessName() == null ? "" : fileWorld.getPossessName().trim();
        if (StringUtils.hasText(possessName) && !characterNameSet.contains(possessName)) {
            log.warn("导入时夺舍角色 [{}] 不存在于角色列表，已清空", possessName);
            possessName = "";
        }
        world.setPossessName(possessName);

        WorldInfo savedWorld;
        if (StringUtils.hasText(targetWorldId)) {
            // 覆盖模式：更新本体（保留背景图），替换其全部角色与知识
            world.setId(targetWorldId);
            savedWorld = updateWorldOrThrow(world);
            worldKnowledgeRepository.deleteCharacterAssocByWorldId(targetWorldId);
            worldKnowledgeRepository.deleteByWorldId(targetWorldId);
            worldCharacterRepository.deleteByWorldId(targetWorldId);
        } else {
            savedWorld = saveWorldOrThrow(world);
        }
        String worldId = savedWorld.getId();

        // —— 角色（新 ID，不含头像）——
        Map<String, String> nameToId = new HashMap<>();
        for (WorldFileCharacter fileCharacter : fileCharacters) {
            WorldCharacter character = new WorldCharacter();
            character.setWorldId(worldId);
            character.setName(fileCharacter.getName().trim());
            character.setIntro(fileCharacter.getIntro() == null ? "" : fileCharacter.getIntro());
            character.setSetting(fileCharacter.getSetting() == null ? "" : fileCharacter.getSetting());
            character.setAiSettings(normalizeJson(fileCharacter.getAiSettings()));
            character.setAvatar(""); // 导出文件不含头像
            WorldCharacter saved = saveCharacterOrThrow(character);
            nameToId.put(saved.getName(), saved.getId());
        }

        // —— 知识（角色名 → 新 ID 关联）——
        for (WorldFileKnowledge fileK : fileKnowledge) {
            WorldKnowledge knowledge = new WorldKnowledge();
            knowledge.setWorldId(worldId);
            String title = fileK.getTitle() == null ? "" : fileK.getTitle().trim();
            if (!StringUtils.hasText(title)) {
                // AI 漏写标题时取内容前 N 字兜底
                String content = fileK.getContent().trim();
                title = content.substring(0, Math.min(DEFAULT_TITLE_LENGTH, content.length()));
            }
            knowledge.setTitle(title);
            knowledge.setContent(fileK.getContent() == null ? "" : fileK.getContent());
            List<String> characterIds = new ArrayList<>();
            for (String name : fileK.getCharacters() == null ? Collections.<String>emptyList() : fileK.getCharacters()) {
                String id = nameToId.get(name);
                if (id != null) {
                    characterIds.add(id);
                }
            }
            knowledge.setCharacterIds(characterIds);
            worldKnowledgeService.save(knowledge);
        }

        return worldInfoService.findById(worldId);
    }

    /** 将文件中的 AI 配置归一化为 JSON 字符串（对象 → 序列化；字符串 → 原样；其他类型 → 序列化） */
    private String normalizeJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String) {
            return StringUtils.hasText((String) value) ? (String) value : "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化 AI 配置失败: {}", e.getMessage());
            return "{}";
        }
    }

    private WorldInfo updateWorldOrThrow(WorldInfo world) {
        try {
            return worldInfoService.update(world);
        } catch (IOException e) {
            throw new RuntimeException("更新世界观失败: " + e.getMessage(), e);
        }
    }

    private WorldInfo saveWorldOrThrow(WorldInfo world) {
        try {
            return worldInfoService.save(world);
        } catch (IOException e) {
            throw new RuntimeException("创建世界观失败: " + e.getMessage(), e);
        }
    }

    private WorldCharacter saveCharacterOrThrow(WorldCharacter character) {
        try {
            return worldCharacterService.save(character);
        } catch (IOException e) {
            throw new RuntimeException("创建角色失败: " + e.getMessage(), e);
        }
    }
}
