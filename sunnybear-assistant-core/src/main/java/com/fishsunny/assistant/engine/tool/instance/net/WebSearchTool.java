package com.fishsunny.assistant.engine.tool.instance.net;

/*
 * @Usage 网页搜索工具 - 支持多种搜索引擎，由 AI 通过 engineName 参数选择
 *
 * Settings 中配置各引擎的 API Key，AI 调用时传 engineName 切换引擎。
 * 国内/中文搜索默认用 metaso，国外/英文搜索可用 serper。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 17:46
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.NetToolKit;
import com.fishsunny.assistant.engine.tool.instance.net.search.SearchEngine;
import com.fishsunny.assistant.engine.tool.instance.net.search.SearchEngineFactory;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ToolKitComponent(NetToolKit.class)
@ConditionalOnExpression("${engine.tool.net.enable:true} && ${engine.tool.net.web-search-tool.enable:true}")
public class WebSearchTool implements ToolHandler {

    public static final String NAME = "web_search_tool";
    public static final String SETTINGS = "web_search_tool_settings";

    private static final List<String> SCOPE_LIST = Arrays.asList("webpage", "document", "scholar", "image", "video", "podcast");

    private static final int DEFAULT_SIZE = 10;
    private static final String DEFAULT_SCOPE = "webpage";
    private static final String DEFAULT_ENGINE = "metaso";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final SearchEngineFactory searchEngineFactory;

    public WebSearchTool(ObjectMapper objectMapper,
                         @Qualifier(SETTINGS) Settings settings,
                         SearchEngineFactory searchEngineFactory) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.searchEngineFactory = searchEngineFactory;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("搜索互联网信息。国内/中文搜索优先使用 metaso，国外/英文搜索可使用 serper。通过 engineName 参数切换。")
                .setRequired(List.of("q"));

        ToolRegister.Parameters qParam = new ToolRegister.Parameters()
                .setParameterName("q")
                .setType("string")
                .setDescription("搜索关键字");

        ToolRegister.Parameters sizeParam = new ToolRegister.Parameters()
                .setParameterName("size")
                .setType("number")
                .setDescription("展示的条目数，默认为" + DEFAULT_SIZE + "条");

        ToolRegister.Parameters scopeParam = new ToolRegister.Parameters()
                .setParameterName("scope")
                .setType("string")
                .setDescription("搜索范围，默认为 webpage，可选值有:" + Arrays.toString(SCOPE_LIST.toArray()));

        ToolRegister.Parameters engineParam = new ToolRegister.Parameters()
                .setParameterName("engineName")
                .setType("string")
                .setDescription("搜索引擎名称，默认为 metaso（国内/中文搜索），可选 serper（国外/英文 Google 搜索）");

        register.setParameters(List.of(qParam, sizeParam, scopeParam, engineParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        // 解析参数
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null) {
                throw new ToolExecutor.ToolExecuteException("参数为空");
            }
            if (!StringUtils.hasText(arguments.getQ())) {
                throw new ToolExecutor.ToolExecuteException("query 参数为空");
            }
            if (arguments.getSize() == null || arguments.getSize() < 1) {
                throw new ToolExecutor.ToolExecuteException("size 参数错误:" + arguments.getSize());
            }
            if (!SCOPE_LIST.contains(arguments.getScope())) {
                throw new ToolExecutor.ToolExecuteException("scope 参数错误:" + arguments.getScope());
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        // AI 通过 engineName 参数选择引擎，默认 metaso
        String engineName = StringUtils.hasText(arguments.getEngineName())
                ? arguments.getEngineName().trim().toLowerCase()
                : DEFAULT_ENGINE;

        // 从 Settings 中取对应引擎的 API Key
        String apiKey = settings.getApiKey(engineName);
        if (!StringUtils.hasText(apiKey)) {
            throw new ToolExecutor.ToolExecuteException(
                    "搜索引擎 [" + engineName + "] 的 API Key 未配置，请在设置中配置。");
        }

        // 工厂创建引擎并执行搜索
        try {
            SearchEngine engine = searchEngineFactory.create(engineName, apiKey);
            String rawJson = engine.search(arguments.getQ(), arguments.getSize(), arguments.getScope());

            Object jsonNode = objectMapper.readTree(rawJson);
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            return new ToolExecutor.ToolExecuteResponse(name(), prettyJson);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException("搜索引擎配置错误: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("搜索引擎调用失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String q;
        private Integer size = DEFAULT_SIZE;
        private String scope = DEFAULT_SCOPE;
        /** AI 选择搜索引擎：metaso（默认，国内/中文）或 serper（国外/英文） */
        private String engineName = DEFAULT_ENGINE;
        private boolean includeSummary = false;
        private boolean includeRawContent = false;
        private boolean conciseSnippet = false;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** MetaSOAI 的 API Key（国内搜索，默认引擎） */
        private String metasoApiKey;
        /** Serper 的 API Key（Google 搜索，国外/英文场景） */
        private String serperApiKey;

        public Settings() {
        }

        public Settings(String metasoApiKey, String serperApiKey) {
            this.metasoApiKey = metasoApiKey;
            this.serperApiKey = serperApiKey;
        }

        /**
         * 根据引擎名称获取对应的 API Key
         */
        public String getApiKey(String engineName) {
            return switch (engineName) {
                case "metaso" -> metasoApiKey;
                case "serper" -> serperApiKey;
                default -> null;
            };
        }
    }
}
