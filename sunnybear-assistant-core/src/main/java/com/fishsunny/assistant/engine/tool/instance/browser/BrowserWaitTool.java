package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器等待工具 - 等待指定元素出现在无头浏览器页面中
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
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.wait.enable:true}")
public class BrowserWaitTool implements ToolHandler {

    public static final String NAME = "browser_wait_tool";

    private static final int DEFAULT_TIMEOUT_MS = 10000;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserWaitTool(ObjectMapper objectMapper,
                           PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("等待指定 CSS 选择器对应的元素出现。用于等待页面加载、AJAX 渲染、弹窗等。")
                .setRequired(List.of("selector"));

        ToolRegister.Parameters selectorParam = new ToolRegister.Parameters()
                .setParameterName("selector")
                .setType("string")
                .setDescription("CSS 选择器，用于定位要等待的元素。例如 '.loading-complete'、'#result-list'、'div.modal'");

        ToolRegister.Parameters timeoutParam = new ToolRegister.Parameters()
                .setParameterName("timeout_ms")
                .setType("integer")
                .setDescription("（可选）超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS);

        register.setParameters(List.of(selectorParam, timeoutParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getSelector())) {
                throw new ToolExecutor.ToolExecuteException("参数 selector 不能为空");
            }

            int timeout = DEFAULT_TIMEOUT_MS;
            if (StringUtils.hasText(arguments.getTimeoutMs())) {
                timeout = Integer.parseInt(arguments.getTimeoutMs());
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            browserService.waitForSelector(sessionId, arguments.getSelector(), timeout);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "元素已出现: " + arguments.getSelector());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("等待元素失败: " + e.getMessage());
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
        private String selector;
        private String timeoutMs;
    }
}
