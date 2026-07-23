package com.fishsunny.assistant.engine.tool.instance.net;

/*
 * @Usage 网页搜索工具 - 通过 MetaSo API 进行网页搜索
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 17:46
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.NetToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ToolKitComponent(NetToolKit.class)
@ConditionalOnExpression("${engine.tool.net.enable:true} && ${engine.tool.net.meta-soai-search.enable:true}")
public class MetaSOAISearchTool implements ToolHandler {

    public static final String NAME = "web_search_tool";
    public static final String SETTINGS = "web_search_tool_settings";

    private static final List<String> SCOPE_LIST = Arrays.asList("webpage", "document", "scholar", "image", "video", "podcast");

    /** HTTP 连接超时时间（秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** HTTP 请求超时时间（秒） */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /** HTTP 成功状态码 */
    private static final int HTTP_OK = 200;

    /** 默认返回条目数 */
    private static final int DEFAULT_SIZE = 10;

    /** 默认搜索范围 */
    private static final String DEFAULT_SCOPE = "webpage";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final HttpClient httpClient;

    public MetaSOAISearchTool(ObjectMapper objectMapper, @Qualifier(SETTINGS) Settings settings) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("通过使用 web_search_tool 你可以使用搜索引擎来获取你想要知道的信息。" +
                        "使用技巧：类似新闻、时政、游戏、健康资讯等实时性或小众专业领域你需要善用搜索。")
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

        register.setParameters(List.of(qParam, sizeParam, scopeParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        if (settings == null || !StringUtils.hasText(settings.getApiKey())) {
            throw new ToolExecutor.ToolExecuteException("搜索工具的 API 密钥未配置，导致无法使用搜索工具。");
        }

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

        try {
            String requestBody = objectMapper.writeValueAsString(arguments);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://metaso.cn/api/v1/search"))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != HTTP_OK) {
                throw new ToolExecutor.ToolExecuteException("搜索引擎返回错误状态码: " + response.statusCode());
            }

            // 格式化 JSON 输出，方便阅读
            Object json = objectMapper.readTree(response.body());
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            return new ToolExecutor.ToolExecuteResponse(name(), prettyJson);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
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
        private boolean includeSummary = false;
        private boolean includeRawContent = false;
        private boolean conciseSnippet = false;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        private String apiKey;
        public Settings() {
        }
        public Settings(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
