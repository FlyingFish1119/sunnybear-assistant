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
import com.fishsunny.assistant.mvc.dao.SessionMemoryJobRepository;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatSessionServiceImplement implements ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionServiceImplement.class);

    private final ChatSessionRepository chatSessionRepository;
    private final SessionFileManager sessionFileManager;

    /** 自动记忆整理待办：local 模式是本地实现，remote 模式是 RPC 代理，两种模式都必然有 bean */
    private final SessionMemoryJobRepository sessionMemoryJobRepository;

    @Autowired
    public ChatSessionServiceImplement(ChatSessionRepository chatSessionRepository,
                                       SessionFileManager sessionFileManager,
                                       SessionMemoryJobRepository sessionMemoryJobRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.sessionFileManager = sessionFileManager;
        this.sessionMemoryJobRepository = sessionMemoryJobRepository;
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
    public List<ChatSession> findByCronIdPage(Integer cronId, int limit, String beforeTime, String beforeId) {
        return chatSessionRepository.selectByCronIdPage(cronId, limit, beforeTime, beforeId);
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
    public Map<String, Object> mergeExtension(String id, Map<String, Object> fields) {
        return chatSessionRepository.mergeExtension(id, fields);
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
        // 连同自动记忆整理记录一起清掉：会话都没了，留着这条只会每次启动被扫一遍再判 skipped
        deleteMemoryJob(id);
        return deleted;
    }

    /** 清理自动记忆待办；remote 模式下走 RPC 代理落到权威库那一侧，正好 */
    private void deleteMemoryJob(String sessionId) {
        try {
            sessionMemoryJobRepository.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("删除会话自动记忆待办失败 [{}]: {}", sessionId, e.getMessage());
        }
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
