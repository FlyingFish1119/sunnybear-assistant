package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器导航工具 - 在无头浏览器中打开指定 URL
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.extension.PlaywrightBrowserService;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.navigate.enable:true}")
public class BrowserNavigateTool implements ToolHandler {

    public static final String NAME = "browser_navigate_tool";

    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserNavigateTool(ObjectMapper objectMapper,
                               PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("在内部无头浏览器中打开指定 URL。会话状态（cookie、登录态等）跨操作保持。")
                .setRequired(List.of("url"));

        ToolRegister.Parameters urlParam = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("目标网页的 URL 地址，须包含协议头（http:// 或 https://）");

        ToolRegister.Parameters timeoutParam = new ToolRegister.Parameters()
                .setParameterName("timeout_ms")
                .setType("integer")
                .setDescription("（可选）页面加载超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS);

        register.setParameters(List.of(urlParam, timeoutParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数 url 不能为空");
            }

            int timeout = DEFAULT_TIMEOUT_MS;
            if (StringUtils.hasText(arguments.getTimeoutMs())) {
                timeout = Integer.parseInt(arguments.getTimeoutMs());
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String title = browserService.navigate(sessionId, arguments.getUrl(), timeout);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已导航到: " + arguments.getUrl() + "\n页面标题: " + title);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器导航失败: " + e.getMessage());
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
        private String url;
        private String timeoutMs;
    }
}
