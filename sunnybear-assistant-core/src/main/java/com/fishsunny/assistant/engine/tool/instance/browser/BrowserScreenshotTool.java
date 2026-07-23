package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器截图工具 - 对无头浏览器当前页面截图，并送AI视觉模型分析页面内容
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.extension.PlaywrightBrowserService;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.screenshot.enable:true}")
public class BrowserScreenshotTool implements ToolHandler {

    public static final String NAME = "browser_screenshot_tool";

    private final AISettings aiSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserScreenshotTool(@Qualifier(AISettings.OCR) AISettings aiSettings,
                                 ChatHttpHandler chatHttpHandler,
                                 ObjectMapper objectMapper,
                                 PlaywrightBrowserService browserService) {
        this.aiSettings = aiSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("对内部无头浏览器当前页面截图并返回中文描述。"
                        + "⚠️ 注意：优先使用 browser_read_content_tool（element 模式）获取页面内容和可交互元素，"
                        + "它的开销更低且返回结构化数据。仅在以下情况使用截图："
                        + "① 页面内容无法表达的视觉信息（布局样式、图表、图片内容）；"
                        + "② 需要确认操作后的视觉变化；"
                        + "③ 页面内容数据不足以判断当前状态。")
                .setRequired(List.of());

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("（可选）描述你希望重点关注的页面区域或内容，AI 会聚焦描述该目标。例如 '导航栏的结构'、'搜索结果的第几条'、'表单的当前填写状态'。不填则对页面进行全面描述。");

        register.setParameters(List.of(targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            // 1. 截取浏览器页面
            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String pageTitle = browserService.getTitle(sessionId);
            String currentUrl = browserService.getCurrentUrl(sessionId);
            String imageBase64 = browserService.screenshot(sessionId);

            // 2. 构建提示词
            String target = arguments.getTarget();
            String prompt;
            if (StringUtils.hasText(target)) {
                prompt = "请用中文描述当前浏览器页面的内容，并重点聚焦以下目标区域：" + target
                        + "\n当前页面标题: " + pageTitle + "\n当前URL: " + currentUrl;
            } else {
                prompt = "请用中文描述当前浏览器页面的内容。\n当前页面标题: " + pageTitle
                        + "\n当前URL: " + currentUrl;
            }

            // 3. 发送给 AI 视觉模型分析
            AtomicReference<String> caption = new AtomicReference<>("");
            ChatMessage message = new ChatMessage().user(prompt, imageBase64);
            ChatRequest request = new ChatRequest()
                    .loadSettings(aiSettings)
                    .setMessages(List.of(message));
            chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                    aiSettings.getStream() != null ? aiSettings.getStream() : true,
                    null,
                    (result, lastRes) -> caption.set(result.content())
            );

            return new ToolExecutor.ToolExecuteResponse(NAME, caption.get());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器截图分析失败: " + e.getMessage());
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
        private String target;
    }
}
