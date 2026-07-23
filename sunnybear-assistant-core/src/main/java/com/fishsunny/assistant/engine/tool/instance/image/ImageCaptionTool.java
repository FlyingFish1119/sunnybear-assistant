package com.fishsunny.assistant.engine.tool.instance.image;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 22:36
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.ImageToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

@ToolKitComponent(ImageToolKit.class)
@ConditionalOnExpression("${engine.tool.image.enable:true} && ${engine.tool.image.image-caption.enable:true}")
public class ImageCaptionTool implements ToolHandler {

    public static final String NAME = "image_caption_tool";
    public static final String SETTINGS = "image_caption_tool_settings";

    private static final Long MAX_TIMEOUT = 15L;

    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";

    private final AISettings aiSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final ToolRegister register;

    @Autowired
    public ImageCaptionTool(@Qualifier(AISettings.OCR) AISettings aiSettings, ChatHttpHandler chatHttpHandler, ObjectMapper objectMapper, @Qualifier(SETTINGS) Settings settings) {
        this.aiSettings = aiSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.settings = settings;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("识别和理解图片/视频内容。支持网络链接和本地文件，返回中文描述。适用于描述图片、识别图中文字、分析图表、理解视频等。")
                .setRequired(List.of("url"));

        ToolRegister.Parameters urlParam = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("图片或视频的URL地址。支持 http/https 网络链接，也支持本地文件绝对路径。当 type 为 image 时，传入图片URL；当 type 为 video 时，传入视频URL。");

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("识别目标，告诉AI你希望从图片/视频中重点了解什么。例如：'描述图片整体内容'、'识别图中的文字'、'判断图片中是否包含错误弹窗'、'分析图表中的数据趋势'。不填则默认对媒体内容进行全面描述。");

        ToolRegister.Parameters typeParam = new ToolRegister.Parameters()
                .setParameterName("type")
                .setType("string")
                .setDescription("媒体类型。可选值：'image'（图片模式，默认值）或 'video'（视频模式）。image模式处理图片URL，video模式处理视频URL。默认为 'image'。");

        register.setParameters(List.of(urlParam, targetParam, typeParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        if (settings.getMaxLength() == null || settings.getMaxLength() < 0) {
            throw new ToolExecutor.ToolExecuteException("工具配置信息错误，maxLength不能小于0。");
        }
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            String type = StringUtils.hasText(arguments.getType()) ? arguments.getType() : TYPE_IMAGE;

            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数错误，url不能为空。");
            }

            if (TYPE_VIDEO.equals(type)) {
                return executeVideoMode(arguments);
            } else {
                return executeImageMode(arguments);
            }
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * image 模式：处理图片URL，构建 ImageContent 发送给 AI。
     */
    private ToolExecutor.ToolExecuteResponse executeImageMode(Arguments arguments) throws Exception {
        String imageBase64 = findImage(arguments);

        String userPrompt = "请用中文解释图片中的内容。\n[任务目标]\n" + arguments.getTarget();
        ImageContent imageContent = new ImageContent(imageBase64);
        ChatMessage message = new ChatMessage().user(userPrompt, imageContent);
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(message));
        return execute(request);
    }

    /**
     * video 模式：处理视频URL，构建 VideoContent 发送给 AI。
     */
    private ToolExecutor.ToolExecuteResponse executeVideoMode(Arguments arguments) throws Exception {
        String videoDataUrl = findVideo(arguments);

        String userPrompt = "请用中文解释视频中的内容。\n[任务目标]\n" + arguments.getTarget();
        VideoContent videoContent = new VideoContent(videoDataUrl);
        ChatMessage message = new ChatMessage().user(userPrompt, videoContent);
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(message));
        return execute(request);

    }
    private ToolExecutor.ToolExecuteResponse execute(ChatRequest request) throws Exception {
        AtomicReference<String> caption = new AtomicReference<>("");
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream() != null ? aiSettings.getStream() : true,
                null,
                (result, lastRes) -> caption.set(result.content()));
        return new ToolExecutor.ToolExecuteResponse(name(), caption.get());
    }

    private String findImage(Arguments arguments) throws Exception {
        if (arguments.getUrl().startsWith("http")) {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(arguments.getUrl()))
                    .timeout(Duration.ofSeconds(MAX_TIMEOUT))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream inputStream = response.body()) {
                String data = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                if (response.statusCode() != 200) {
                    throw new ToolExecutor.ToolExecuteException(data);
                }
                if (!data.startsWith("data:image")) {
                    throw new ToolExecutor.ToolExecuteException("返回的图片格式错误");
                }
                return data;
            }
        } else {
            File file = new File(arguments.getUrl());
            try (InputStream inputStream = new FileInputStream(file)) {
                byte[] bytes = inputStream.readAllBytes();
                MultipartScaleImageHelper helper = new MultipartScaleImageHelper(bytes);
                byte[] afterScale = helper.scaleImage(settings.getMaxLength());
                return ScaleImageHelper.byteArrayToBase64(afterScale);
            }
        }
    }

    /**
     * 读取视频文件或下载网络视频，编码为 dataUrl。
     */
    private String findVideo(Arguments arguments) throws Exception {
        if (arguments.getUrl().startsWith("http")) {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(arguments.getUrl()))
                    .timeout(Duration.ofSeconds(MAX_TIMEOUT))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() != 200) {
                throw new ToolExecutor.ToolExecuteException("下载视频失败：" + new String(bytes, StandardCharsets.UTF_8));
            }
            return ObjectUtils.encodeToDataUrl(arguments.getUrl(), bytes);
        } else {
            File file = new File(arguments.getUrl());
            try (FileInputStream inputStream = new FileInputStream(file)) {
                byte[] bytes = inputStream.readAllBytes();
                return ObjectUtils.encodeToDataUrl(arguments.getUrl(), bytes);
            }
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
    public static class Arguments{
        private String url;
        private String target;
        private String type;
    }

    @Data
    @Accessors
    public static class Settings{
        private Integer maxLength;
        public Settings(){
        }
        public Settings(Integer maxLength){
            this.maxLength = maxLength;
        }
    }
}