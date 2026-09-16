package com.fishsunny.assistant.plug.world.remote;

/*
 * @Usage 世界观模块远程仓储装配 —— mode=remote 时用代理 bean 顶替本地实现
 *
 * 与 core 的 RemoteRepositoryConfig 同一套机制，只是把世界观模块的仓库单独收在这里。
 * 实现类打了 mode=local 条件，此处补上代理 bean，上层按接口注入即可。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.plug.world.repository.WorldCharacterRepository;
import com.fishsunny.assistant.plug.world.repository.WorldInfoRepository;
import com.fishsunny.assistant.plug.world.repository.WorldKnowledgeRepository;
import com.fishsunny.assistant.remote.RemoteRepositoryFactory;
import com.fishsunny.assistant.remote.RepoRpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorldRepoProxyConfig {

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public WorldInfoRepository worldInfoRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(WorldInfoRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public WorldCharacterRepository worldCharacterRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(WorldCharacterRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public WorldKnowledgeRepository worldKnowledgeRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(WorldKnowledgeRepository.class, repoRpcClient, objectMapper);
    }
}
