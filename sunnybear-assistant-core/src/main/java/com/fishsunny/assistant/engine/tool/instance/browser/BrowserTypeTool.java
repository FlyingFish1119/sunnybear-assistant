package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器输入工具 - 在无头浏览器中输入文本
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.engine.tool.service.browser.PlaywrightBrowserService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.type.enable:true}")
public class BrowserTypeTool implements ToolHandler {

    public static final String NAME = "browser_type_tool";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserTypeTool(ObjectMapper objectMapper,
                           PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("在输入框中填入文本。用于 input、textarea 等表单元素。")
                .setRequired(List.of("selector", "text"));

        ToolRegister.Parameters selectorParam = new ToolRegister.Parameters()
                .setParameterName("selector")
                .setType("string")
                .setDescription("CSS 选择器，用于定位输入框元素。例如 '#username'、'input[name=\"q\"]'、'.search-input'");

        ToolRegister.Parameters textParam = new ToolRegister.Parameters()
                .setParameterName("text")
                .setType("string")
                .setDescription("要输入的文本内容");

        register.setParameters(List.of(selectorParam, textParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getSelector())) {
                throw new ToolExecutor.ToolExecuteException("参数 selector 不能为空");
            }
            if (!StringUtils.hasText(arguments.getText())) {
                throw new ToolExecutor.ToolExecuteException("参数 text 不能为空");
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            browserService.type(sessionId, arguments.getSelector(), arguments.getText());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已在 [" + arguments.getSelector() + "] 中输入文本: " + arguments.getText());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器输入失败: " + e.getMessage());
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
        private String text;
    }
}
