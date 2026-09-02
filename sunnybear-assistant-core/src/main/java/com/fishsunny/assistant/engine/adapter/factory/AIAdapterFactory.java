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
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.http.HttpClient;
import java.util.*;

@Slf4j
public class AIAdapterFactory {

    /** 非流式适配器配方表；volatile 支持热替换（reload 后新请求即按新配方制造适配器） */
    private volatile Map<String, AIAdapterRegister> adapterMap = new HashMap<>();

    /** 流式适配器配方表 */
    private volatile Map<String, AIAdapterRegister> streamAdapterMap = new HashMap<>();

    private final HttpClient httpClient;

    @Autowired
    public AIAdapterFactory(AIAdapterProperties properties, HttpClient httpClient) {
        this.httpClient = httpClient;
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
        log.info("expected adapter count: {}, success adapter count: {}", registers.size(), successCount);
    }

    public AIAdapterRegister getRegister(String apiName, boolean stream) {
        return stream ? streamAdapterMap.get(apiName) : adapterMap.get(apiName);
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
                .setApiKey(register.getApiKey())
                .setMasterReqCls(register.getMasterReqCls())
                .setTargetReqCls(register.getTargetReqCls())
                .setMasterRespCls(register.getMasterRespCls())
                .setTargetRespCls(register.getTargetRespCls())
                .setHttpClient(httpClient);

        try {
            return register.getAdapterCls().getConstructor(AIAdapterOption.class).newInstance(option);
        } catch (Exception e) {
            log.error("Get adapter failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
