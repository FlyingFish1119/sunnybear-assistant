package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观服务实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.repository.WorldCharacterRepository;
import com.fishsunny.assistant.plug.world.repository.WorldInfoRepository;
import com.fishsunny.assistant.plug.world.repository.WorldKnowledgeRepository;
import com.fishsunny.assistant.plug.world.repository.WorldSessionMappingRepository;
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
public class WorldInfoServiceImplement implements WorldInfoService {

    private static final Logger log = LoggerFactory.getLogger(WorldInfoServiceImplement.class);

    /** 背景图操作锁 */
    private final Object backgroundLock = new Object();

    /** 背景图最大尺寸（长边） */
    private static final int BACKGROUND_MAX_PX = 1920;

    private final WorldInfoRepository worldInfoRepository;
    private final WorldCharacterRepository worldCharacterRepository;
    private final WorldKnowledgeRepository worldKnowledgeRepository;
    private final WorldSessionMappingRepository worldSessionMappingRepository;
    private final String worldDir;

    @Autowired
    public WorldInfoServiceImplement(WorldInfoRepository worldInfoRepository,
                                     WorldCharacterRepository worldCharacterRepository,
                                     WorldKnowledgeRepository worldKnowledgeRepository,
                                     WorldSessionMappingRepository worldSessionMappingRepository,
                                     @Value("${assistant.file.base-path:data/}") String fileBasePath) {
        this.worldInfoRepository = worldInfoRepository;
        this.worldCharacterRepository = worldCharacterRepository;
        this.worldKnowledgeRepository = worldKnowledgeRepository;
        this.worldSessionMappingRepository = worldSessionMappingRepository;
        this.worldDir = fileBasePath + "worlds/";
    }

    @Override
    public WorldInfo findById(String id) {
        return worldInfoRepository.selectById(id);
    }

    @Override
    public List<WorldInfo> findAll() {
        return worldInfoRepository.selectAll();
    }

    @Override
    public WorldInfo save(WorldInfo worldInfo) throws IOException {
        if (worldInfo.getId() == null || worldInfo.getId().isEmpty()) {
            worldInfo.setId(UUID.randomUUID().toString());
        }
        // 背景图由 uploadBackground() 单独处理，创建时统一为空
        worldInfo.setBackground("");
        worldInfo.setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        clearIllegal(worldInfo);
        return worldInfoRepository.insert(worldInfo);
    }

    @Override
    public WorldInfo update(WorldInfo worldInfo) throws IOException {
        if (!StringUtils.hasText(worldInfo.getId())) {
            throw new RuntimeException("世界观 ID 不能为空");
        }
        clearIllegal(worldInfo);

        // 背景图由 uploadBackground() / deleteBackground() 单独处理，这里始终保留旧值
        WorldInfo existing = findById(worldInfo.getId());
        worldInfo.setBackground(existing.getBackground());
        worldInfo.setUpdateTime(LocalDateTime.now());
        return worldInfoRepository.update(worldInfo);
    }

    private void clearIllegal(WorldInfo worldInfo) throws IOException {
        if (!StringUtils.hasText(worldInfo.getDescription())) {
            worldInfo.setDescription("");
        }
        if (!StringUtils.hasText(worldInfo.getPreset())) {
            worldInfo.setPreset("");
        }
        if (!StringUtils.hasText(worldInfo.getMainColor())) {
            worldInfo.setMainColor("");
        }
        if (!StringUtils.hasText(worldInfo.getPossessName())) {
            worldInfo.setPossessName("");
        }
        if (worldInfo.getPrivateChatEnable() == null) {
            worldInfo.setPrivateChatEnable(false);
        }
        if (worldInfo.getNarrationEnable() == null) {
            worldInfo.setNarrationEnable(true);
        }
        if (worldInfo.getMaxRounds() == null) {
            worldInfo.setMaxRounds(5);
        }
        if (!StringUtils.hasText(worldInfo.getSchedulerAiSettings())) {
            worldInfo.setSchedulerAiSettings("{}");
        }
    }

    @Override
    public WorldInfo deleteBackground(String id) {
        synchronized (backgroundLock) {
            WorldInfo worldInfo = findById(id);
            if (worldInfo == null) {
                throw new RuntimeException("世界观不存在");
            }
            // 删文件
            if (worldInfo.getBackground() != null && !worldInfo.getBackground().isEmpty()) {
                File bgFile = new File(worldInfo.getBackground());
                if (bgFile.exists() && !bgFile.delete()) {
                    log.warn("删除世界观背景图失败 [file={}]", bgFile.getAbsolutePath());
                    throw new RuntimeException("删除世界观背景图失败");
                }
            }
            // 清 DB
            worldInfoRepository.updateBackground(id, "");
            worldInfo.setBackground("");
            return worldInfo;
        }
    }

    @Override
    public String uploadBackground(String id, MultipartFile file) {
        synchronized (backgroundLock) {
            WorldInfo worldInfo = findById(id);
            if (worldInfo == null) {
                throw new RuntimeException("世界观不存在");
            }
            try {
                File worldInfoDir = new File(worldDir + id);
                if (!worldInfoDir.exists() && !worldInfoDir.mkdirs()) {
                    log.error("创建世界观 [{}] 目录失败", id);
                    throw new RuntimeException("创建世界观目录失败");
                }
                byte[] scaled = new MultipartScaleImageHelper(file)
                        .scaleImage(BACKGROUND_MAX_PX);
                String filename = "background.jpg";
                Path target = Paths.get(worldInfoDir.getPath(), filename);
                Files.write(target, scaled);
                // 更新 DB
                String path = target.toString().replace('\\', '/');
                worldInfoRepository.updateBackground(id, path);
                log.info("世界观 [{}] 背景图已上传: {}", id, path);
                return path;
            } catch (IOException e) {
                log.error("上传世界观 [{}] 背景图失败", id, e);
                throw new RuntimeException("上传背景图失败: " + e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public WorldInfo deleteById(String id) {
        // 级联删除：先会话映射，再知识（含其角色关联），再角色，再世界观本体
        worldSessionMappingRepository.deleteByWorldId(id);
        worldKnowledgeRepository.deleteCharacterAssocByWorldId(id);
        worldKnowledgeRepository.deleteByWorldId(id);
        worldCharacterRepository.deleteByWorldId(id);
        WorldInfo deleted = worldInfoRepository.deleteById(id);
        // 清理世界观文件目录（data/worlds/{worldId}/）
        deleteWorldDir(id);
        return deleted;
    }

    // ==================== 内部方法 ====================

    /** 删除世界观的文件目录 */
    private void deleteWorldDir(String worldId) {
        File worldDirFile = new File(worldDir + worldId);
        if (!worldDirFile.exists()) return;
        try {
            try (var s = Files.walk(worldDirFile.toPath())) {
                s.sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
            }
            log.info("已删除世界观文件目录: {}", worldDirFile.getPath());
        } catch (Exception e) {
            log.warn("删除世界观文件目录失败: {}", e.getMessage());
        }
    }
}
