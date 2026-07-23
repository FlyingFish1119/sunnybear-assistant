package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器点击工具 - 在无头浏览器中点击页面元素
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
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.click.enable:true}")
public class BrowserClickTool implements ToolHandler {

    public static final String NAME = "browser_click_tool";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserClickTool(ObjectMapper objectMapper,
                            PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("点击匹配 CSS 选择器的第一个可交互元素。适用于按钮、链接、复选框等。")
                .setRequired(List.of("selector"));

        ToolRegister.Parameters selectorParam = new ToolRegister.Parameters()
                .setParameterName("selector")
                .setType("string")
                .setDescription("CSS 选择器，用于定位目标元素。例如 '#submit-btn'、'.nav-link'、'button[type=\"submit\"]'");

        register.setParameters(List.of(selectorParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getSelector())) {
                throw new ToolExecutor.ToolExecuteException("参数 selector 不能为空");
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            browserService.click(sessionId, arguments.getSelector());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已点击元素: " + arguments.getSelector());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器点击失败: " + e.getMessage());
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
    }
}
