package com.fishsunny.assistant.engine.adapter.factory;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 07:58
 */

import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.adapter.AIAdapterProperties;
import com.fishsunny.assistant.engine.adapter.AIAdapterRegister;
import com.fishsunny.assistant.engine.adapter.ModelInfo;
import com.fishsunny.assistant.engine.tts.TTSClient;
import com.fishsunny.assistant.engine.tts.TTSSettings;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AIAdapterFactory {

    /** 非流式适配器配方表；volatile 支持热替换（reload 后新请求即按新配方制造适配器） */
    private volatile Map<String, AIAdapterRegister> adapterMap = new HashMap<>();

    /** 流式适配器配方表 */
    private volatile Map<String, AIAdapterRegister> streamAdapterMap = new HashMap<>();

    /** 模型列表缓存（Caffeine 自动过期）；配方热加载时整体作废 */
    private final Cache<String, List<ModelInfo>> modelCache;

    /** 模型列表缓存时长（分钟）；<=0 表示不缓存 */
    private final long modelCacheTtlMinutes;

    private final HttpClient httpClient;

    /** TTS 客户端：enable 时随每个 adapter 实例注入（工厂是唯一拿得到 bean 的创建点） */
    private final TTSClient ttsClient;
    private final TTSSettings ttsSettings;

    @Autowired
    public AIAdapterFactory(AIAdapterProperties properties, @Qualifier("aiHttpClient") HttpClient httpClient,
                            TTSClient ttsClient, TTSSettings ttsSettings) {
        this.httpClient = httpClient;
        this.ttsClient = ttsClient;
        this.ttsSettings = ttsSettings;
        this.modelCacheTtlMinutes = Math.max(0, properties.getModelCacheTtlMinutes());
        this.modelCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(Math.max(1, this.modelCacheTtlMinutes), TimeUnit.MINUTES)
                .build();
        List<AIAdapterRegister> registers = properties.getRegister();
        if (CollectionUtils.isEmpty(registers)) {
            throw new IllegalStateException("No adapter registered. Please configure 'adapter-register.register' in application.yml");
        }
        rebuild(registers);
    }

    /**
     * 热替换适配器配方（工厂本体不变，重新制造产品）：
     * 之后每次 getAdapter() 产出的适配器实例都将基于新配置。
     * 解析或加载全部失败时抛异常，由调用方决定保留旧配方。
     */
    public synchronized void reload(List<AIAdapterRegister> registers) {
        if (CollectionUtils.isEmpty(registers)) {
            throw new IllegalArgumentException("Register list is empty, keep current adapters");
        }
        rebuild(registers);
        log.info("Adapter config reloaded, available adapters: {}", getAvailableAdapterNames());
    }

    /** 按新的 register 列表重建配方表；全部失败时抛异常，旧配方不受影响 */
    private void rebuild(List<AIAdapterRegister> registers) {
        Map<String, AIAdapterRegister> newAdapterMap = new HashMap<>();
        Map<String, AIAdapterRegister> newStreamAdapterMap = new HashMap<>();
        int successCount = 0;
        for (AIAdapterRegister register : registers) {
            try {
                if (register.getStream()) {
                    newStreamAdapterMap.put(register.getApiName(), register);
                } else {
                    newAdapterMap.put(register.getApiName(), register);
                }
                successCount++;
            } catch (Exception e) {
                log.error("Add adapter failed: {}", e.getMessage());
            }
        }
        if (successCount == 0) {
            throw new IllegalArgumentException("No adapter loaded from register list");
        }
        this.adapterMap = newAdapterMap;
        this.streamAdapterMap = newStreamAdapterMap;
        // 配方变了，模型列表缓存（随 modelUrl）也一并作废
        modelCache.invalidateAll();
        log.info("expected adapter count: {}, success adapter count: {}", registers.size(), successCount);
    }

    public AIAdapterRegister getRegister(String apiName, boolean stream) {
        return stream ? streamAdapterMap.get(apiName) : adapterMap.get(apiName);
    }

    /** 按 apiName 取配方，不限流式与否（优先非流式） */
    public AIAdapterRegister getRegister(String apiName) {
        AIAdapterRegister register = adapterMap.get(apiName);
        return register != null ? register : streamAdapterMap.get(apiName);
    }

    /**
     * 拉取指定适配器的可选模型列表。未配置 modelUrl 时返回空列表（前端回退到手填）。
     * 结果按 apiName 经 Caffeine 缓存（modelCacheTtlMinutes 内复用），配方热加载时整体作废。
     * 模型列表与流式与否无关，复用任意一条配方制造适配器即可。
     */
    public List<ModelInfo> listModels(String apiName) throws Exception {
        AIAdapterRegister register = getRegister(apiName);
        if (register == null) {
            throw new IllegalArgumentException("未知的适配器: " + apiName);
        }
        if (!StringUtils.hasText(register.getModelUrl())) {
            return List.of();
        }
        if (modelCacheTtlMinutes > 0) {
            List<ModelInfo> cached = modelCache.getIfPresent(apiName);
            if (cached != null) {
                return cached;
            }
        }
        List<ModelInfo> models = getAdapter(apiName, !adapterMap.containsKey(apiName)).listModels();
        if (modelCacheTtlMinutes > 0) {
            modelCache.put(apiName, models);
        }
        return models;
    }

    /**
     * 获取所有已注册的适配器名称（去重）
     */
    public Set<String> getAvailableAdapterNames() {
        Set<String> names = new LinkedHashSet<>(adapterMap.keySet());
        names.addAll(streamAdapterMap.keySet());
        return names;
    }


    public AIAdapter getAdapter(String apiName, boolean stream) {
        AIAdapterRegister register = stream ? streamAdapterMap.get(apiName) : adapterMap.get(apiName);
        if (register == null) {
            throw new RuntimeException("Adapter not found");
        }

        AIAdapterOption option = new AIAdapterOption()
                .setBaseUrl(register.getBaseUrl())
                .setModelUrl(register.getModelUrl())
                .setApiKey(register.getApiKey())
                .setHeaders(register.getHeaders())
                .setMasterReqCls(register.getMasterReqCls())
                .setTargetReqCls(register.getTargetReqCls())
                .setMasterRespCls(register.getMasterRespCls())
                .setTargetRespCls(register.getTargetRespCls())
                .setHttpClient(httpClient)
                .setTtsClient(Boolean.TRUE.equals(ttsSettings.getEnable()) ? ttsClient : null);

        try {
            return register.getAdapterCls().getConstructor(AIAdapterOption.class).newInstance(option);
        } catch (Exception e) {
            log.error("Get adapter failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
