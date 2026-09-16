package com.fishsunny.assistant.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.mvc.dao.ChatMessageRepository;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.tool.bot.enable=false")
class RepoRpcEndToEndTest {

    private static final Path DB_DIR;

    static {
        try {
            DB_DIR = Files.createTempDirectory("repo-rpc-e2e");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + DB_DIR.resolve("e2e.db").toAbsolutePath().toString().replace("\\", "/"));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chatSessionOverRealWebSocket() {
        String id = "rpc-e2e-" + UUID.randomUUID();
        RepoRpcClient client = new RepoRpcClient(objectMapper, "ws://127.0.0.1:" + port + "/ws/repo", 5000);
        try {
            ChatSessionRepository repository =
                    RemoteRepositoryFactory.create(ChatSessionRepository.class, client, objectMapper);

            assertNull(repository.selectById(id));

            ChatSession session = new ChatSession("远端会话")
                    .setId(id)
                    .setCreateTime(LocalDateTime.of(2026, 9, 15, 12, 0, 0))
                    .setUpdateTime(LocalDateTime.of(2026, 9, 15, 12, 0, 0));
            repository.insert(session);

            ChatSession fetched = repository.selectById(id);
            assertNotNull(fetched);
            assertEquals("远端会话", fetched.getName());
            assertEquals(1, repository.selectByType("chat").stream()
                    .filter(s -> s.getId().equals(id)).count());

            repository.mergeExtension(id, java.util.Map.of("characterId", "c9"));
            assertEquals("c9", repository.selectById(id).getExtension().get("characterId"));

            ChatSession deleted = repository.deleteById(id);
            assertEquals(id, deleted.getId());
            assertNull(repository.selectById(id));
        } finally {
            client.close();
        }
    }

    @Test
    void chatMessageOverRealWebSocket() {
        String sessionId = "rpc-e2e-msg-session-" + UUID.randomUUID();
        String messageId = "rpc-e2e-msg-" + UUID.randomUUID();
        RepoRpcClient client = new RepoRpcClient(objectMapper, "ws://127.0.0.1:" + port + "/ws/repo", 5000);
        try {
            ChatMessageRepository repository =
                    RemoteRepositoryFactory.create(ChatMessageRepository.class, client, objectMapper);

            ChatMessage message = new ChatMessage()
                    .setId(messageId)
                    .setSessionId(sessionId)
                    .setName("tester")
                    .setCreateTime(LocalDateTime.of(2026, 9, 15, 13, 0, 0))
                    .setActive(true);
            message.user("看这张图", new ImageContent("http://example.com/a.png"));

            repository.insert(message);

            ChatMessage loaded = repository.selectById(messageId);
            assertNotNull(loaded);
            assertEquals("user", loaded.getRole());
            List<MessageContent> contents = loaded.getContents();
            assertEquals(2, contents.size());
            assertInstanceOf(TextContent.class, contents.get(0));
            assertInstanceOf(ImageContent.class, contents.get(1));
            assertEquals("看这张图", ((TextContent) contents.get(0)).getContent());

            assertEquals(1, repository.deleteBySessionId(sessionId));
            assertNull(repository.selectById(messageId));
        } finally {
            client.close();
        }
    }
}
