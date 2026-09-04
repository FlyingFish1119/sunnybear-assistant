package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器截图工具 - 对无头浏览器当前页面截图。capture_type=analyze 送内部 AI 视觉模型分析返回文本；
 *        capture_type=raw 不调用 AI，直接把截屏图片以多模态 tool content 数组返回
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.engine.tool.service.SystemPrompts;
import com.fishsunny.assistant.engine.tool.service.browser.PlaywrightBrowserService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.screenshot.enable:true}")
public class BrowserScreenshotTool implements ToolHandler, MultimodalResultAble {

    public static final String NAME = "browser_screenshot_tool";

    /** capture_type 常量：analyze = 现有行为（截屏后交给内部 AI 识别返回文本）；raw = 直接以多模态 content 数组返回截屏图片 */
    private static final String CAPTURE_TYPE_ANALYZE = "analyze";
    private static final String CAPTURE_TYPE_RAW = "raw";

    /** 会话文件落盘根路径 */
    @Value("${assistant.file.base-path:data/}")
    private String basePath;

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
                .setDescription("""
                        截取当前浏览器页面。captureType=analyze（默认）时截屏后由内部 AI 识别返回中文描述（截图仅用于视觉确认：布局、图表、图片；优先用 browser_read_content_tool 获取结构化内容）；
                        captureType=raw 时不调用内部 AI，直接把截屏图片通过多模态 tool content 数组返回，供你直接查看截图图片进行分析。""")
                .setRequired(List.of());

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("（可选）描述你希望重点关注的页面区域或内容。captureType=analyze 时 AI 会聚焦描述该目标；captureType=raw 时忽略。例如 '导航栏的结构'、'搜索结果的第几条'、'表单的当前填写状态'。不填则对页面进行全面描述。");

        ToolRegister.Parameters captureTypeParam = new ToolRegister.Parameters()
                .setParameterName("captureType")
                .setType("string")
                .setDescription("截图返回方式。可选值：'analyze'（默认，截屏后由内部 AI 识别并返回中文描述）或 'raw'（截屏后不调用 AI，直接把截屏图片以多模态内容返回，由你直接查看图片并自行分析）。默认为 'analyze'。");

        register.setParameters(List.of(targetParam, captureTypeParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            String captureType = StringUtils.hasText(arguments.getCaptureType()) ? arguments.getCaptureType() : CAPTURE_TYPE_ANALYZE;

            // 1. 截取浏览器页面（analyze/raw 两种返回方式共享）
            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String pageTitle = browserService.getTitle(sessionId);
            String currentUrl = browserService.getCurrentUrl(sessionId);
            String imageBase64 = browserService.screenshot(sessionId);

            if (CAPTURE_TYPE_RAW.equals(captureType)) {
                return executeRawMode(context, imageBase64, pageTitle, currentUrl);
            }
            return executeAnalyzeMode(arguments, imageBase64, pageTitle, currentUrl);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器截图失败: " + e.getMessage());
        }
    }

    /**
     * raw 模式：截屏后不调用内部 AI，直接把页面截图以多模态 tool 消息的 content 数组返回，
     * 供外层模型直接查看截图自行分析。图片按约定落盘到会话文件目录，路径记录在返回文本中。
     *
     * @param context      工具执行上下文，需包含 chatSession 以构建会话文件路径
     * @param imageBase64  截屏图片的 Base64 数据
     * @param pageTitle    当前页面标题
     * @param currentUrl   当前页面 URL
     * @return 携带截屏图片多模态内容的工具回复
     */
    private ToolExecutor.ToolExecuteResponse executeRawMode(Map<String, Object> context, String imageBase64,
                                                            String pageTitle, String currentUrl) throws Exception {
        // action 已声明 chatSession 依赖（@ToolIncludeContext），此处直接取用
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        Path imagePath = chatSession.buildSessionFilePath(basePath).resolve(UUID.randomUUID() + ".png");
        String result = "已截取当前浏览器页面。\n"
                + "当前页面标题: " + pageTitle + "\n"
                + "当前URL: " + currentUrl + "\n"
                + "图片已保存至：" + imagePath;
        return new ToolExecutor.ToolExecuteResponse(name(), result)
                .modalContent(imagePath.toString(), ContentTypeVariable.IMAGE, imageBase64);
    }

    /**
     * analyze 模式：截屏后交由内部 AI 视觉模型用中文描述页面内容，可聚焦 target 指定区域。
     */
    private ToolExecutor.ToolExecuteResponse executeAnalyzeMode(Arguments arguments, String imageBase64,
                                                                String pageTitle, String currentUrl) throws Exception {
        String target = arguments.getTarget();
        String prompt;
        if (StringUtils.hasText(target)) {
            prompt = "请用中文描述当前浏览器页面的内容，并重点聚焦以下目标区域：" + target
                    + "\n当前页面标题: " + pageTitle + "\n当前URL: " + currentUrl;
        } else {
            prompt = "请用中文描述当前浏览器页面的内容。\n当前页面标题: " + pageTitle
                    + "\n当前URL: " + currentUrl;
        }

        AtomicReference<String> caption = new AtomicReference<>("");
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.OCR),
                        new ChatMessage().userWithImage(prompt, imageBase64)
                ));
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream() == null || aiSettings.getStream(),
                null,
                (result, lastRes) -> caption.set(result.content())
        );

        return new ToolExecutor.ToolExecuteResponse(NAME, caption.get());
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
        private String captureType;
    }
}
