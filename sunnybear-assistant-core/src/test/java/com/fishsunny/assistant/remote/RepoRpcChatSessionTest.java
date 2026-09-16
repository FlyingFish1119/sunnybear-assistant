package com.fishsunny.assistant.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoRpcChatSessionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    static class StubChatSessionRepository implements ChatSessionRepository {

        private final Map<String, ChatSession> db = new LinkedHashMap<>();

        @Override
        public ChatSession insert(ChatSession chatSession) {
            db.put(chatSession.getId(), chatSession);
            return chatSession;
        }

        @Override
        public ChatSession update(ChatSession chatSession) {
            db.put(chatSession.getId(), chatSession);
            return chatSession;
        }

        @Override
        public Map<String, Object> mergeExtension(String id, Map<String, Object> fields) {
            ChatSession session = db.get(id);
            if (session == null) {
                throw new RuntimeException("Session not found: " + id);
            }
            Map<String, Object> extension = session.ensureExtension();
            extension.putAll(fields);
            return extension;
        }

        @Override
        public ChatSession deleteById(String id) {
            ChatSession removed = db.remove(id);
            if (removed == null) {
                throw new RuntimeException("Session not found");
            }
            return removed;
        }

        @Override
        public List<ChatSession> selectAll() {
            return new ArrayList<>(db.values());
        }

        @Override
        public List<ChatSession> selectByType(String type) {
            return db.values().stream().filter(s -> type.equals(s.getType())).toList();
        }

        @Override
        public List<ChatSession> selectByTypePage(String type, int limit, String beforeTime, String beforeId) {
            return selectByType(type).stream().limit(limit).toList();
        }

        @Override
        public ChatSession selectById(String id) {
            return db.get(id);
        }

        @Override
        public List<ChatSession> selectByTypeAndExtensionValue(String type, String jsonKey, String value) {
            return List.of();
        }
    }
}
