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
import com.fishsunny.assistant.utils.SessionFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionServiceImplement implements ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionServiceImplement.class);

    private final ChatSessionRepository chatSessionRepository;
    private final SessionFileManager sessionFileManager;

    @Autowired
    public ChatSessionServiceImplement(ChatSessionRepository chatSessionRepository,
                                       SessionFileManager sessionFileManager) {
        this.chatSessionRepository = chatSessionRepository;
        this.sessionFileManager = sessionFileManager;
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
    public List<ChatSession> findByTypePage(String type, int limit, String beforeTime, String beforeId) {
        return chatSessionRepository.selectByTypePage(type, limit, beforeTime, beforeId);
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
    public List<ChatSession> findByTypeAndExtensionValue(String type, String jsonKey, String value) {
        return chatSessionRepository.selectByTypeAndExtensionValue(type, jsonKey, value);
    }

    @Override
    public ChatSession deleteById(String id) {
        ChatSession deleted = chatSessionRepository.deleteById(id);
        // 删除会话对应的文件目录
        deleteSessionFileDir(id);
        return deleted;
    }

    /**
     * 删除会话对应的文件目录（{basePath}/session/{sessionId}）
     */
    private void deleteSessionFileDir(String sessionId) {
        try {
            sessionFileManager.deleteSessionDir(sessionId);
        } catch (Exception e) {
            log.warn("删除会话文件目录失败 [{}]: {}", sessionId, e.getMessage());
        }
    }
}
