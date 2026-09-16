package com.fishsunny.assistant.plug.character.remote;

/*
 * @Usage 角色模块远程仓储装配 —— mode=remote 时用代理 bean 顶替本地实现
 *
 * 与 core 的 RemoteRepositoryConfig 同一套机制，只是把角色模块的仓库单独收在这里。
 * 实现类打了 mode=local 条件，此处补上代理 bean，上层按接口注入即可。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.plug.character.repository.CharacterGlossaryRepository;
import com.fishsunny.assistant.plug.character.repository.CharacterInfoRepository;
import com.fishsunny.assistant.remote.RemoteRepositoryFactory;
import com.fishsunny.assistant.remote.RepoRpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CharacterRepoProxyConfig {

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public CharacterInfoRepository characterInfoRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(CharacterInfoRepository.class, repoRpcClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "remote")
    public CharacterGlossaryRepository characterGlossaryRepository(RepoRpcClient repoRpcClient, ObjectMapper objectMapper) {
        return RemoteRepositoryFactory.create(CharacterGlossaryRepository.class, repoRpcClient, objectMapper);
    }
}
