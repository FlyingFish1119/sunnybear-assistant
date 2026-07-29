package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 02:14
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionServiceImplement implements ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionServiceImplement.class);

    @Value("${assistant.file.base-path:}")
    private String basePath;

    private final ChatSessionRepository chatSessionRepository;

    @Autowired
    public ChatSessionServiceImplement(ChatSessionRepository chatSessionRepository) {
        this.chatSessionRepository = chatSessionRepository;
    }

    @Override
    public ChatSession findById(String id) {
        return chatSessionRepository.selectById(id);
    }

    @Override
    public List<ChatSession> findAll() {
        return chatSessionRepository.selectAll();
    }

    @Override
    public List<ChatSession> findByType(String type) {
        return chatSessionRepository.selectByType(type);
    }

    @Override
    public ChatSession save(ChatSession chatSession) {
        chatSession.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        return chatSessionRepository.insert(chatSession);
    }

    @Override
    public ChatSession update(ChatSession chatSession) {
        chatSession.setUpdateTime(LocalDateTime.now());
        return chatSessionRepository.update(chatSession);
    }

    @Override
    public ChatSession deleteById(String id) {
        ChatSession deleted = chatSessionRepository.deleteById(id);
        // 删除会话对应的文件目录
        deleteSessionFileDir(id);
        return deleted;
    }

       /**
     * 删除会话对应的文件目录
     */
    private void deleteSessionFileDir(String sessionId) {
        try {
            if (!StringUtils.hasText(basePath)) {
                basePath = System.getProperty("user.dir") + "/session";
            }
            Path dirPath = Paths.get(basePath, sessionId);
            if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                // 递归删除目录
                deleteDirectoryRecursively(dirPath.toFile());
                log.info("已删除会话文件目录: {}", dirPath);
            }
        } catch (Exception e) {
            log.warn("删除会话文件目录失败 [{}]: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 递归删除目录及其所有内容
     *
     * @param directory 要删除的目录
     */
    private void deleteDirectoryRecursively(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // 递归删除子目录
                    deleteDirectoryRecursively(file);
                } else {
                    // 删除文件
                    if (!file.delete()) {
                        log.warn("删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        // 删除目录本身
        if (!directory.delete()) {
            log.warn("删除目录失败: {}", directory.getAbsolutePath());
        }
    }
}
