package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器拖拽工具 - 在无头浏览器中将一个元素拖拽到另一个元素
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
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.drag.enable:true}")
public class BrowserDragTool implements ToolHandler {

    public static final String NAME = "browser_drag_tool";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserDragTool(ObjectMapper objectMapper,
                           PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("将元素从 source 拖拽到 target 位置。适用于拖拽排序、滑块等场景。")
                .setRequired(List.of("source", "target"));

        ToolRegister.Parameters sourceParam = new ToolRegister.Parameters()
                .setParameterName("source")
                .setType("string")
                .setDescription("源元素的 CSS 选择器，即被拖拽的元素");

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("目标元素的 CSS 选择器，即拖拽到的位置");

        register.setParameters(List.of(sourceParam, targetParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getSource())) {
                throw new ToolExecutor.ToolExecuteException("参数 source 不能为空");
            }
            if (!StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空");
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            browserService.drag(sessionId, arguments.getSource(), arguments.getTarget());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已将 [" + arguments.getSource() + "] 拖拽到 [" + arguments.getTarget() + "]");
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("拖拽失败: " + e.getMessage());
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
        private String source;
        private String target;
    }
}
