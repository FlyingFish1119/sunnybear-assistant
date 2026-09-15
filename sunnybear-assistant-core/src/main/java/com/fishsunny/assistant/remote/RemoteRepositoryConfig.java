package com.fishsunny.assistant.remote;

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
import com.fishsunny.assistant.mvc.dao.ChatMessageRepository;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteRepositoryConfig {

    @ConditionalOnProperty(prefix = "assistant.storage.mode", havingValue = "remote")
    public RepoRpcClient repoRpcClient(ObjectMapper objectMapper,
                                       @Value("${assistant.storage.remote-url:ws://127.0.0.1:11451/ws/repo}") String remoteUrl,
                                       @Value("${assistant.storage.timeout-ms:15000}") long timeoutMs) {
        return new RepoRpcClient(objectMapper, remoteUrl, timeoutMs);
    }

    /** 新增要远程化的仓库，就照抄一个这样的 bean；同时给它的 Implement 打上 mode=local 条件 */
    @Bean
    @ConditionalOnProperty(prefix = "assistant.storage", name = "mode", havingValue = "remote")
    public ChatSessionRepository chatSessionRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(ChatSessionRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "assistant.storage", name = "mode", havingValue = "remote")
    public ChatMessageRepository chatMessageRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(ChatMessageRepository.class, repoRpcClient, objectMapper);
    }
}
