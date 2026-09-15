package com.fishsunny.assistant.remote.config;

/*
 * @Usage 远程仓储装配 —— mode=remote 时用代理 bean 顶替本地实现，mode=local（默认）时完全不生效
 *
 * 切换只发生在本地这一侧：云端保持 mode=local，照常读写自己的 SQLite，作为唯一权威库。
 * 本地把对应 Repository 的实现类用 @ConditionalOnProperty(mode=local) 排除掉，
 * 再由这里补上代理 bean，上层（service/controller）按接口注入，一行都不用改。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.mvc.dao.AiGreetingRepository;
import com.fishsunny.assistant.mvc.dao.ChatMessageRepository;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import com.fishsunny.assistant.mvc.dao.CronJobRepository;
import com.fishsunny.assistant.mvc.dao.KnowledgeRepository;
import com.fishsunny.assistant.mvc.dao.MemoryRepository;
import com.fishsunny.assistant.mvc.dao.SessionKnowledgeRepository;
import com.fishsunny.assistant.mvc.dao.TaskPromptRepository;
import com.fishsunny.assistant.mvc.dao.TaskRepository;
import com.fishsunny.assistant.mvc.dao.TaskStepRepository;
import com.fishsunny.assistant.remote.RemoteRepositoryFactory;
import com.fishsunny.assistant.remote.RepoRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RemoteRepositoryConfig {

    private final RemoteRepositoryProperties remoteRepositoryProperties;

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public RepoRpcClient repoRpcClient(ObjectMapper objectMapper) {
        return new RepoRpcClient(objectMapper, remoteRepositoryProperties);
    }

    /** 新增要远程化的仓库，就照抄一个这样的 bean；同时给它的 Implement 打上 mode=local 条件 */
    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public AiGreetingRepository aiGreetingRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(AiGreetingRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public ChatSessionRepository chatSessionRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(ChatSessionRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public ChatMessageRepository chatMessageRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(ChatMessageRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public CronJobRepository cronJobRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(CronJobRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public KnowledgeRepository knowledgeRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(KnowledgeRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public MemoryRepository memoryRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(MemoryRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public SessionKnowledgeRepository sessionKnowledgeRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(SessionKnowledgeRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public TaskPromptRepository taskPromptRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(TaskPromptRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public TaskRepository taskRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(TaskRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public TaskStepRepository taskStepRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(TaskStepRepository.class, repoRpcClient, objectMapper);
    }
}
