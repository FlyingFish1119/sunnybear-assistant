package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器滚动工具 - 在无头浏览器中滚动页面或指定容器
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
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.scroll.enable:true}")
public class BrowserScrollTool implements ToolHandler {

    public static final String NAME = "browser_scroll_tool";

    private static final int DEFAULT_DELTA_Y = 300;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserScrollTool(ObjectMapper objectMapper,
                             PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("垂直滚动页面或指定容器。正值向下，负值向上。")
                .setRequired(List.of());

        ToolRegister.Parameters selectorParam = new ToolRegister.Parameters()
                .setParameterName("selector")
                .setType("string")
                .setDescription("（可选）CSS 选择器，定位要滚动的容器元素（如带滚动条的 div）。不填则滚动整个页面。");

        ToolRegister.Parameters deltaYParam = new ToolRegister.Parameters()
                .setParameterName("delta_y")
                .setType("integer")
                .setDescription("（可选）垂直滚动像素数，正数向下、负数向上，默认 " + DEFAULT_DELTA_Y);

        register.setParameters(List.of(selectorParam, deltaYParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            int deltaY = DEFAULT_DELTA_Y;
            if (StringUtils.hasText(arguments.getDeltaY())) {
                deltaY = Integer.parseInt(arguments.getDeltaY());
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String target;
            if (StringUtils.hasText(arguments.getSelector())) {
                browserService.scroll(sessionId, arguments.getSelector(), deltaY);
                target = "元素 [" + arguments.getSelector() + "]";
            } else {
                browserService.scroll(sessionId, deltaY);
                target = "整个页面";
            }

            String direction = deltaY >= 0 ? "向下" : "向上";
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    target + "已" + direction + "滚动 " + Math.abs(deltaY) + " 像素");
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器滚动失败: " + e.getMessage());
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
        private String deltaY;
    }
}
