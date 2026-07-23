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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AIAdapterFactory {

    private final static Logger logger = LoggerFactory.getLogger(AIAdapterFactory.class);

    private final Map<String, AIAdapterRegister> adapterMap = new HashMap<>();

    private final Map<String, AIAdapterRegister> streamAdapterMap = new HashMap<>();

    private final HttpClient httpClient;

    @Autowired
    public AIAdapterFactory(AIAdapterProperties properties, HttpClient httpClient) {
        this.httpClient = httpClient;
        List<AIAdapterRegister> registers = properties.getRegister();
        int successCount = 0;
        if (CollectionUtils.isEmpty(registers)) {
            throw new IllegalStateException("No adapter registered. Please configure 'adapter-register.register' in application.yml");
        }

        for (AIAdapterRegister register : registers) {
            try {
                addAdapter(register);
                successCount++;
            } catch (Exception e) {
                logger.error("Add adapter failed: {}", e.getMessage());
            }
        }
        logger.info("expected adapter count: {}, success adapter count: {}", registers.size(), successCount);
    }

    private void addAdapter(AIAdapterRegister register) {
        if (register.getStream()) {
            streamAdapterMap.put(register.getApiName(), register);
        } else {
            adapterMap.put(register.getApiName(), register);
        }
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
            logger.error("Get adapter failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
