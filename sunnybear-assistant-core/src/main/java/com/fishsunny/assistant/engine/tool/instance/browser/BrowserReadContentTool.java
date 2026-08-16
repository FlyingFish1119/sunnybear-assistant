package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器页面阅读工具 - 读取无头浏览器当前页面，默认由轻量AI提取可交互元素列表，也支持返回完整HTML
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
import com.fishsunny.assistant.engine.tool.instance.SystemPrompts;
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
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.read-content.enable:true}")
public class BrowserReadContentTool implements ToolHandler {

    public static final String NAME = "browser_read_content_tool";

    private static final String MODE_ELEMENT = "element";
    private static final String MODE_FULL = "full";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;
    private final AISettings taskAISettings;
    private final ChatHttpHandler chatHttpHandler;

    public BrowserReadContentTool(ObjectMapper objectMapper,
                                  PlaywrightBrowserService browserService,
                                  @Qualifier(AISettings.CUB) AISettings taskAISettings,
                                  ChatHttpHandler chatHttpHandler) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;
        this.taskAISettings = taskAISettings;
        this.chatHttpHandler = chatHttpHandler;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("读取页面内容。element 模式返回可交互元素列表，full 模式返回完整 HTML。")
                .setRequired(List.of());

        ToolRegister.Parameters modeParam = new ToolRegister.Parameters()
                .setParameterName("mode")
                .setType("string")
                .setDescription("""
                        读取模式，默认 'element'。\
                        'element' - 提取可交互元素，返回精简的 CSS 选择器列表；\
                        'full' - 返回完整页面 HTML，适合需要深入分析页面结构时使用。""");

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("（当模式为 element 时必选）在 element 模式下描述你需要的元素类型或区域，例如 '所有输入框和按钮'、'导航栏的链接列表'。不填则提取全部可交互元素。full 模式下忽略。");

        register.setParameters(List.of(modeParam, targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            String mode = MODE_ELEMENT;
            if (StringUtils.hasText(arguments.getMode())) {
                mode = arguments.getMode().toLowerCase().trim();
            }

            if (!MODE_ELEMENT.equals(mode) && !MODE_FULL.equals(mode)) {
                throw new ToolExecutor.ToolExecuteException(
                        "无效的 mode 参数: " + mode + "，仅支持 '" + MODE_ELEMENT + "' 或 '" + MODE_FULL + "'");
            }

            if (MODE_ELEMENT.equals(mode) && !StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("在 element 模式下，必须提供 target 参数");
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String title = browserService.getTitle(sessionId);
            String url = browserService.getCurrentUrl(sessionId);
            String html = browserService.getContent(sessionId);

            if (MODE_FULL.equals(mode)) {
                return buildFullResult(title, url, html);
            } else {
                return buildElementResult(title, url, html, arguments.getTarget());
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("读取页面内容失败: " + e.getMessage());
        }
    }

    /**
     * full 模式：直接返回清洗后的完整 HTML（代码块包裹，避免前端渲染）
     */
    private ToolExecutor.ToolExecuteResponse buildFullResult(String title, String url, String html) {
        String result = "当前页面: " + title + "\nURL: " + url + "\n\n```html\n" + html + "\n```";
        return new ToolExecutor.ToolExecuteResponse(NAME, result);
    }

    /**
     * element 模式：将清洗后的 HTML 交给轻量 AI 提取可交互元素，返回精简列表
     */
    private ToolExecutor.ToolExecuteResponse buildElementResult(String title, String url, String html, String target)
            throws Exception {
        String focus = StringUtils.hasText(target)
                ? "请重点提取以下目标：" + target + "。"
                : "请提取页面上所有可交互元素。";

        String prompt = """
                页面标题: ${title}
                页面URL: ${url}
                任务: ${focus}

                要求:
                1. 列出可交互元素（按钮、输入框、下拉框、链接、复选框等），以CSS选择器形式给出。
                2. 每个元素标注：选择器、元素类型、可见文本/占位符、推荐操作（click/type/select等）。
                3. 只输出有实际交互价值的元素，忽略装饰性元素和页脚信息。
                4. 输出格式如下：
                   - #kw (type) | input#kw | 搜索框 | placeholder="请输入"
                   - #su (click) | input#su | 搜索按钮 | value="百度一下"
                   - 新闻 (click) | a.mnav[name="tj_trnews"] | 导航链接
                5. 输出尽量精简，不要输出分析过程或补充说明。
                """
                .replace("${title}", StringUtils.hasText(title) ? title : "无")
                .replace("${url}", url)
                .replace("${focus}", focus);

        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.SUMMARY),
                        new ChatMessage().user(prompt + "\n\n页面HTML:\n```html\n" + html + "\n```")
                ))
                .loadSettings(taskAISettings);

        AtomicReference<String> result = new AtomicReference<>("");
        chatHttpHandler.translate(UUID.randomUUID().toString(), taskAISettings.getAdapterName(), request,
                taskAISettings.getStream() != null ? taskAISettings.getStream() : true,
                null,
                (r, lastRes) -> result.set(r.content())
        );

        return new ToolExecutor.ToolExecuteResponse(NAME, result.get());
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
        private String mode;
        private String target;
    }
}
