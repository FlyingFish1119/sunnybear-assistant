package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色信息服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.repository.CharacterGlossaryRepository;
import com.fishsunny.assistant.plug.character.repository.CharacterInfoRepository;
import com.fishsunny.assistant.utils.image.Database64ScaleImageHelper;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class CharacterInfoServiceImplement implements CharacterInfoService {

    private static final Logger log = LoggerFactory.getLogger(CharacterInfoServiceImplement.class);

    /** 背景图操作锁 */
    private final Object backgroundLock = new Object();

    /** 背景图最大尺寸（长边） */
    private static final int BACKGROUND_MAX_PX = 1920;

    private final CharacterInfoRepository characterInfoRepository;
    private final CharacterGlossaryRepository glossaryRepository;
    private final ChatSessionService chatSessionService;
    private final String backgroundDir;

    @Autowired
    public CharacterInfoServiceImplement(CharacterInfoRepository characterInfoRepository,
                                          CharacterGlossaryRepository glossaryRepository,
                                          ChatSessionService chatSessionService,
                                          @Value("${assistant.file.base-path:data/}") String fileBasePath) {
        this.characterInfoRepository = characterInfoRepository;
        this.glossaryRepository = glossaryRepository;
        this.chatSessionService = chatSessionService;
        this.backgroundDir = fileBasePath + "characters/";
    }

    @Override
    public CharacterInfo findById(String id) {
        return characterInfoRepository.selectById(id);
    }

    @Override
    public List<CharacterInfo> findAll() {
        return characterInfoRepository.selectAll();
    }

    @Override
    public CharacterInfo save(CharacterInfo characterInfo) throws IOException {
        if (characterInfo.getId() == null || characterInfo.getId().isEmpty()) {
            characterInfo.setId(UUID.randomUUID().toString());
        }
        // 背景图由 uploadBackground() 单独处理，创建时统一为空
        characterInfo.setBackground("");
        characterInfo.setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        clearIllegal(characterInfo);
        return characterInfoRepository.insert(characterInfo);
    }

    @Override
    public CharacterInfo update(CharacterInfo characterInfo) throws IOException {
        if (!StringUtils.hasText(characterInfo.getId())) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        // 背景图由 uploadBackground() / deleteBackground() 单独处理，这里始终保留旧值
        CharacterInfo existing = findById(characterInfo.getId());
        characterInfo.setBackground(existing.getBackground());
        // 快捷选项配置前端未必随 body 提交：未提供时保留旧值，避免被清成默认
        if (!StringUtils.hasText(characterInfo.getChatSelect()) && StringUtils.hasText(existing.getChatSelect())) {
            characterInfo.setChatSelect(existing.getChatSelect());
        }
        clearIllegal(characterInfo);
        characterInfo.setUpdateTime(LocalDateTime.now());
        return characterInfoRepository.update(characterInfo);
    }

    private void clearIllegal(CharacterInfo characterInfo) throws IOException {
        if (!StringUtils.hasText(characterInfo.getAvatar())) {
            characterInfo.setAvatar("");
        } else {
            Database64ScaleImageHelper helper = new Database64ScaleImageHelper(characterInfo.getAvatar());
            characterInfo.setAvatar(helper.scaleImage(256));
        }
        if (!StringUtils.hasText(characterInfo.getAiSettings())) {
            characterInfo.setAiSettings("{}");
        }
        if (!StringUtils.hasText(characterInfo.getPreset())) {
            characterInfo.setPreset("");
        }
        if (!StringUtils.hasText(characterInfo.getMainColor())) {
            characterInfo.setMainColor("");
        }
        if (characterInfo.getOpacity() == null) {
            characterInfo.setOpacity(0.85);
        }
        if (!StringUtils.hasText(characterInfo.getTools())) {
            characterInfo.setTools("{}");
        }
        if (!StringUtils.hasText(characterInfo.getChatSelect())) {
            characterInfo.setChatSelect("{}");
        }
    }

    @Override
    public CharacterInfo deleteBackground(String id) {
        synchronized (backgroundLock) {
            CharacterInfo character = findById(id);
            if (character == null) {
                throw new RuntimeException("角色不存在");
            }
            // 删文件
            if (character.getBackground() != null && !character.getBackground().isEmpty()) {
                File bgFile = new File(character.getBackground());
                if (bgFile.exists() && !bgFile.delete()) {
                    log.warn("删除角色背景图失败 [file={}]", bgFile.getAbsolutePath());
                    throw new RuntimeException("删除角色背景图失败");
                }
            }
            // 清 DB
            characterInfoRepository.updateBackground(id, "");
            character.setBackground("");
            return character;
        }
    }

    @Override
    public String uploadBackground(String id, MultipartFile file) {
        synchronized (backgroundLock) {
            CharacterInfo character = findById(id);
            if (character == null) {
                throw new RuntimeException("角色不存在");
            }
            try {
                File charDir = new File(backgroundDir + id);
                if (!charDir.exists() && !charDir.mkdirs()) {
                    log.error("创建角色 [{}] 目录失败", id);
                    throw new RuntimeException("创建角色目录失败");
                }
                byte[] scaled = new MultipartScaleImageHelper(file)
                        .scaleImage(BACKGROUND_MAX_PX);
                String filename = "background.jpg";
                Path target = Paths.get(charDir.getPath(), filename);
                Files.write(target, scaled);
                // 更新 DB
                String path = target.toString().replace('\\', '/');
                characterInfoRepository.updateBackground(id, path);
                log.info("角色 [{}] 背景图已上传: {}", id, path);
                return path;
            } catch (IOException e) {
                log.error("上传角色 [{}] 背景图失败", id, e);
                throw new RuntimeException("上传背景图失败: " + e.getMessage());
            }
        }
    }
    @Override
    @Transactional
    public CharacterInfo deleteById(String id) {
        // 级联删除：绑定该角色的所有会话一并删除
        List<ChatSession> boundSessions = chatSessionService.findByTypeAndExtensionValue(
                CharacterSessionBindings.SESSION_TYPE, CharacterSessionBindings.EXTENSION_KEY, id);
        for (ChatSession session : boundSessions) {
            try {
                chatSessionService.deleteById(session.getId());
            } catch (Exception e) {
                log.warn("删除角色关联会话失败 [sessionId={}]: {}", session.getId(), e.getMessage());
            }
        }
        glossaryRepository.deleteByCharacterId(id);
        CharacterInfo deleted = characterInfoRepository.deleteById(id);
        // 清理角色文件目录（data/characters/{characterId}/）
        deleteCharDir(id);
        return deleted;
    }

    // ==================== 内部方法 ====================

    /** 删除角色的文件目录 */
    private void deleteCharDir(String characterId) {
        File charDir = new File(backgroundDir + characterId);
        if (!charDir.exists()) return;
        try {
            try (var s = Files.walk(charDir.toPath())) {
                s.sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
            }
            log.info("已删除角色文件目录: {}", charDir.getPath());
        } catch (Exception e) {
            log.warn("删除角色文件目录失败: {}", e.getMessage());
        }
    }
}
