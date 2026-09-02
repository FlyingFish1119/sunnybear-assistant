package com.fishsunny.assistant.engine.tool.instance.image;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 23:50
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.ImageToolKit;
import com.fishsunny.assistant.engine.tool.service.SystemPrompts;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(ImageToolKit.class)
@ConditionalOnExpression("${engine.tool.image.enable:true} && ${engine.tool.image.screen-capture.enable:true}")
public class ScreenCaptureTool implements ToolHandler {

    public static final String NAME = "screen_capture_tool";

    /** 图片缩放的最大宽度（像素） */
    private static final int MAX_IMAGE_WIDTH = 1920;

    /** 模式常量 */
    private static final String MODE_LOCATION = "location";
    private static final String MODE_CAPTION = "caption";

    private final ToolRegister register;

    private final AISettings aiSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;

    public ScreenCaptureTool(@Qualifier(AISettings.OCR) AISettings aiSettings,
                             ChatHttpHandler chatHttpHandler,
                             ObjectMapper objectMapper
    ) {
        this.aiSettings = aiSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        截取屏幕并分析。location 模式返回 UI 元素归一化坐标（用于自动化点击），caption 模式返回屏幕内容的中文描述。""")
                .setRequired(List.of("target", "mode"));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("要定位或描述的目标UI元素描述。在 location 模式下必填，需要尽可能具体地描述元素的外观特征，以便AI在屏幕截图中精确定位。例如：'蓝色的提交按钮'、'左上角的文件菜单'、'用户名输入框'、'弹出窗口的关闭按钮'、'桌面上的Chrome图标'。在 caption 模式下可选，用于聚焦描述指定区域，不填则对屏幕进行全面描述。");

        ToolRegister.Parameters modeParam = new ToolRegister.Parameters()
                .setParameterName("mode")
                .setType("string")
                .setDescription("工具模式。可选值：'location'（定位模式，截取屏幕后分析UI元素坐标位置，返回归一化坐标0-1）或 'caption'（描述模式，截取屏幕后使用AI用中文描述屏幕内容，支持 target 参数聚焦描述）。默认为 'location'。");

        register.setParameters(List.of(targetParam, modeParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            String mode = StringUtils.hasText(arguments.getMode()) ? arguments.getMode() : MODE_LOCATION;

            // 截取屏幕并压缩（两种模式共享）
            String imageBase64 = captureAndScaleScreen();

            if (MODE_CAPTION.equals(mode)) {
                return executeCaptionMode(arguments, imageBase64);
            } else {
                return executeLocationMode(arguments, imageBase64);
            }
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * 截取当前屏幕并压缩为 Base64 编码的图片数据。
     * 该方法被 location 和 caption 两种模式共享复用。
     *
     * @return 压缩后的图片 Base64 字符串
     * @throws Exception 截屏或压缩过程中发生的异常
     */
    private String captureAndScaleScreen() throws Exception {
        // 1. 创建 Robot 实例
        Robot robot = new Robot();

        // 2. 获取屏幕尺寸
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle screenRect = new Rectangle(screenSize);

        // 3. 截取屏幕（返回 BufferedImage）
        BufferedImage capture = robot.createScreenCapture(screenRect);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(capture, "png", outputStream);

        // 4. 压缩图片
        byte[] bytes = outputStream.toByteArray();
        MultipartScaleImageHelper helper = new MultipartScaleImageHelper(bytes);
        byte[] afterScale = helper.scaleImage(MAX_IMAGE_WIDTH);
        return ScaleImageHelper.byteArrayToBase64(afterScale);
    }

    /**
     * location 模式：截取屏幕后，通过 AI 视觉模型定位指定 UI 元素，返回归一化坐标。
     *
     * @param arguments  工具参数，包含 target 描述
     * @param imageBase64 截屏压缩后的 Base64 图片数据
     * @return AI 返回的归一化坐标信息
     * @throws Exception AI 调用过程中发生的异常
     */
    private ToolExecutor.ToolExecuteResponse executeLocationMode(Arguments arguments, String imageBase64) throws Exception {
        if (!StringUtils.hasText(arguments.getTarget())) {
            throw new ToolExecutor.ToolExecuteException("目标为空");
        }
        String prompt = """
                目标: ${target}
                请输出目标在图片中的归一化坐标（x,y,w,h，值域0-1，相对于图片宽高）。
                要求:
                1. 坐标尽可能准确。
                2. 输出尽可能精简，只保留核心坐标信息即可。
                """.replace("${target}", arguments.getTarget());

        // 发送给 AI 识别
        AtomicReference<String> caption = new AtomicReference<>("");
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.OCR),
                        new ChatMessage().userWithImage(prompt, imageBase64)
                ));
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream(),
                null,
                ((result, lastRes) -> caption.set(result.content()))
        );

        return new ToolExecutor.ToolExecuteResponse(name(), caption.get());
    }

    /**
     * caption 模式：截取屏幕后，使用 AI 用中文描述屏幕内容。
     * 如果用户指定了 target 参数，则聚焦描述该目标区域。
     *
     * @param arguments  工具参数，包含可选的 target 描述
     * @param imageBase64 截屏压缩后的 Base64 图片数据
     * @return AI 返回的屏幕内容中文描述
     * @throws Exception AI 调用过程中发生的异常
     */
    private ToolExecutor.ToolExecuteResponse executeCaptionMode(Arguments arguments, String imageBase64) throws Exception {
        String target = arguments.getTarget();
        String prompt;
        if (StringUtils.hasText(target)) {
            prompt = "请用中文描述屏幕上的内容，并重点聚焦以下目标区域：\n" + target;
        } else {
            prompt = "请用中文描述屏幕上的内容。";
        }

        AtomicReference<String> caption = new AtomicReference<>("");
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.OCR),
                        new ChatMessage().userWithImage(prompt, imageBase64)
                ));
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream(),
                null,
                ((result, lastRes) -> caption.set(result.content()))
        );

        return new ToolExecutor.ToolExecuteResponse(name(), caption.get());
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
    @Accessors
    public static class Arguments{
        private String target;
        private String mode;
        public Arguments(){
        }
        public Arguments(String target){
            this.target = target;
        }
    }
}