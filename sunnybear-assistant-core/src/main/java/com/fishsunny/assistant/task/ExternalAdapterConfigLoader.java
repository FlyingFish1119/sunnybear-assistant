package com.fishsunny.assistant.task;

/*
 * @Usage 从外部 application.yml 解析 adapter-register 段 —— register 是纯 POJO，直接用 Jackson 解析 + Class.forName 装配，
 *        不经过 Spring 绑定；${ENV} 占位符用 Environment 手动解析
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/14
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterRegister;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExternalAdapterConfigLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Environment environment;

    public ExternalAdapterConfigLoader(Environment environment) {
        this.environment = environment;
    }

    /** 解析外部 application.yml 的 engine.adapter-register.register 段；段缺失或为空时返回空列表 */
    public List<AIAdapterRegister> load(Resource resource) throws Exception {
        JsonNode root = yamlMapper.readTree(resource.getInputStream());
        JsonNode registerNode = root.path("engine").path("adapter-register").path("register");
        List<AIAdapterRegister> registers = new ArrayList<>();
        if (!registerNode.isArray()) {
            return registers;
        }
        for (JsonNode node : registerNode) {
            AIAdapterRegister register = new AIAdapterRegister()
                    .setApiName(text(node, "apiName"))
                    .setBaseUrl(resolve(text(node, "baseUrl")))
                    .setApiKey(resolve(text(node, "apiKey")))
                    .setStream(node.has("stream") ? node.get("stream").asBoolean() : null)
                    .setAdapterCls(loadClass(node, "adapterCls", AIAdapter.class))
                    .setMasterReqCls(loadClass(node, "masterReqCls", AIRequest.class))
                    .setTargetReqCls(loadClass(node, "targetReqCls", AIRequest.class))
                    .setMasterRespCls(loadClass(node, "masterRespCls", AIResponse.class))
                    .setTargetRespCls(loadClass(node, "targetRespCls", AIResponse.class));
            registers.add(register);
        }
        return registers;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 解析 ${ENV} 占位符（未定义的保持原样） */
    private String resolve(String value) {
        return value == null ? null : environment.resolvePlaceholders(value);
    }

    @SuppressWarnings("unchecked")
    private <T> Class<? extends T> loadClass(JsonNode node, String field, Class<T> type) {
        String name = text(node, field);
        if (name == null) {
            return null;
        }
        try {
            return (Class<? extends T>) Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("class not found: " + name, e);
        }
    }
}
