package com.fishsunny.assistant.engine.tool.instance.net.search;

/*
 * @Usage 搜索引擎工厂
 *
 * 根据引擎名称和 API Key 创建对应的 SearchEngine 实例。
 * 新增搜索引擎时在此注册即可。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/6
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

@Component
public class SearchEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchEngineFactory.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    public SearchEngineFactory(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public SearchEngine create(String engineName, String apiKey) {
        if (engineName == null || engineName.isBlank()) {
            throw new IllegalArgumentException("搜索引擎名称不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("搜索引擎 [" + engineName + "] 的 API Key 不能为空");
        }

        String normalizedName = engineName.trim().toLowerCase();

        SearchEngine engine = switch (normalizedName) {
            case MetaSOAISearchEngine.ENGINE_NAME -> new MetaSOAISearchEngine(apiKey, objectMapper, httpClient);
            case SerperSearchEngine.ENGINE_NAME  -> new SerperSearchEngine(apiKey, objectMapper, httpClient);
            default -> throw new IllegalArgumentException(
                    "未知的搜索引擎: \"" + engineName + "\"，支持的引擎: metaso, serper");
        };

        log.info("创建搜索引擎实例: {}", engine.getName());
        return engine;
    }
}
