package com.fishsunny.assistant.engine.tool.instance.image;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 22:36
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ContentType;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.ViewToolKit;
import com.fishsunny.assistant.engine.tool.service.SystemPrompts;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(ViewToolKit.class)
@ConditionalOnExpression("${engine.tool.view.enable:true} && ${engine.tool.view.view-caption.enable:true}")
public class ViewCaptionTool implements ToolHandler, MultimodalResultAble {

    public static final String NAME = "view_caption_tool";
    public static final String SETTINGS = "view_caption_tool_settings";

    /** capture_type 常量：analyze = 现有行为（内部 AI 识别返回文本）；raw = 直接把原图/原视频以多模态 content 数组返回 */
    private static final String CAPTURE_TYPE_ANALYZE = "analyze";
    private static final String CAPTURE_TYPE_RAW = "raw";

    private static final Long MAX_TIMEOUT = 15L;
    private static final Integer DEFAULT_IMAGE_LENGTH = 1024;

    /**
     * 视频大小上限（字节）：视频不再缩放、整段 base64 进请求体，过大既撑爆内存也过不了
     * 上游请求体限制（Kimi 约 100MB），超过直接给出可操作的提示。
     */
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;

    private final ToolRegister register;

    private final AISettings aiSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Settings settings;

    @Autowired
    public ViewCaptionTool(@Qualifier(AISettings.OCR) AISettings aiSettings,
                            ChatHttpHandler chatHttpHandler,
                            ObjectMapper objectMapper,
                            @Qualifier(SETTINGS) Settings settings,
                            HttpClient httpClient
    ) {
        this.aiSettings = aiSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.httpClient = httpClient;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("识别和理解图片或视频内容，或（captureType=raw）直接把原图/原视频以多模态 content 数组返回给上层模型查看。支持网络链接和本地文件路径。analyze 模式返回中文描述，适用于描述图片、描述视频、识别图中文字、分析图表等。")
                .setRequired(List.of("url"));

        ToolRegister.Parameters urlParam = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("图片或视频的URL地址。支持 http/https 网络链接，也支持本地文件绝对路径。");

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("识别目标，告诉AI你希望从图片/视频中重点了解什么。例如：'描述图片整体内容'、'描述视频讲了什么'、'识别图中的文字'、'判断图片中是否包含错误弹窗'、'分析图表中的数据趋势'。不填则默认对图片/视频进行全面描述。captureType=raw 时忽略。");

        ToolRegister.Parameters captureTypeParam = new ToolRegister.Parameters()
                .setParameterName("captureType")
                .setType("string")
                .setDescription("返回方式。可选值：'analyze'（默认，内部 AI 识别并返回中文描述）或 'raw'（不调用 AI，直接把原图/原视频以多模态内容返回，由你直接查看分析）。默认为 'analyze'。");

        register.setParameters(List.of(urlParam, targetParam, captureTypeParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        if (settings.getMaxLength() == null || settings.getMaxLength() < 0) {
            throw new ToolExecutor.ToolExecuteException("工具配置信息错误，maxLength不能小于0。");
        }
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            String captureType = StringUtils.hasText(arguments.getCaptureType()) ? arguments.getCaptureType() : CAPTURE_TYPE_ANALYZE;

            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数错误，url不能为空。");
            }

            if (CAPTURE_TYPE_RAW.equals(captureType)) {
                return executeRawImageMode(arguments);
            }
            return executeImageMode(arguments);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    /**
     * raw 模式：不调用内部 AI，按内部 OCR 同一套解析（网络 data URI / 本地文件缩放）
     * 拿到媒体后落盘为会话目录下的文件，并以多模态 tool content 数组返回，供外层模型直接查看原图/原视频。
     */
    private ToolExecutor.ToolExecuteResponse executeRawImageMode(Arguments arguments) throws Exception {
        String dataUri = findMedia(arguments);
        boolean isVideo = ContentType.VIDEO.equals(ObjectUtils.detectFileType(dataUri));
        byte[] bytes = Base64Utils.decodeBase64FromDataUri(dataUri);
        if (bytes == null || bytes.length == 0) {
            throw new ToolExecutor.ToolExecuteException("媒体数据为空，无法执行 raw 返回模式");
        }
        String fileName = UUID.randomUUID() + "." + extractImageSubtype(dataUri);
        String result = (isVideo ? "已获取视频。" : "已获取图片。") + "\n"
                + (isVideo ? "视频" : "图片") + "已保存至会话文件目录：" + fileName;
        return new ToolExecutor.ToolExecuteResponse(name(), result)
                .modalContent(fileName, isVideo ? ContentType.VIDEO : ContentType.IMAGE,
                        ObjectUtils.encodeToDataUrl(fileName, bytes));
    }

    /**
     * 从 data URI（如 data:image/jpeg;base64,...）提取图片子类型作为文件后缀，缺省返回 png。
     */
    private String extractImageSubtype(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return "png";
        }
        int colonIdx = dataUri.indexOf(':');
        int semicolonIdx = dataUri.indexOf(';');
        if (semicolonIdx > colonIdx) {
            String mimeType = dataUri.substring(colonIdx + 1, semicolonIdx);
            int slashIdx = mimeType.indexOf('/');
            String subType = slashIdx >= 0 ? mimeType.substring(slashIdx + 1) : mimeType;
            if (subType.contains("+")) {
                subType = subType.substring(0, subType.indexOf('+'));
            }
            return subType.isBlank() ? "png" : subType;
        }
        return "png";
    }

    /**
     * image/video 模式：处理媒体URL，构建 ImageContent / VideoContent 发送给 AI。
     */
    private ToolExecutor.ToolExecuteResponse executeImageMode(Arguments arguments) throws Exception {
        String dataUri = findMedia(arguments);
        boolean isVideo = ContentType.VIDEO.equals(ObjectUtils.detectFileType(dataUri));
        String userPrompt = (isVideo ? "请用中文描述视频中的内容。" : "请用中文解释图片中的内容。")
                + "\n[任务目标]\n" + arguments.getTarget();
        ChatMessage userMessage = new ChatMessage();
        if (isVideo) {
            userMessage.userWithVideo(userPrompt, dataUri);
        } else {
            userMessage.userWithImage(userPrompt, dataUri);
        }
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.OCR),
                        userMessage
                ));
        return execute(request);
    }

    private ToolExecutor.ToolExecuteResponse execute(ChatRequest request) throws Exception {
        AtomicReference<String> caption = new AtomicReference<>("");
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                aiSettings.getStream(),
                null,
                (result, lastRes) -> caption.set(result.content()));
        return new ToolExecutor.ToolExecuteResponse(name(), caption.get());
    }

    /**
     * 读取本地/网络媒体并返回 data URI。
     * <p>图片按 maxLength 缩放后再编码（内部 OCR 同一套口径）；视频不做图像缩放，
     * 按真实 MIME 编码原样返回——以前把视频塞进 ImageIO 当图片处理，拿到的是 NPE 或乱码。
     */
    private String findMedia(Arguments arguments) throws Exception {
        if (arguments.getUrl().startsWith("http")) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(arguments.getUrl()))
                    .timeout(Duration.ofSeconds(MAX_TIMEOUT))
                    .build();
            HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream inputStream = response.body()) {
                String data = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                if (response.statusCode() != 200) {
                    throw new ToolExecutor.ToolExecuteException(data);
                }
                if (!data.startsWith("data:image") && !data.startsWith("data:video")) {
                    throw new ToolExecutor.ToolExecuteException("返回的媒体格式错误，只支持图片或视频 data URI");
                }
                return data;
            }
        } else {
            File file = new File(arguments.getUrl());
            if (!file.exists() || !file.isFile()) {
                throw new ToolExecutor.ToolExecuteException("本地文件不存在：" + arguments.getUrl());
            }
            String type = ObjectUtils.detectFileTypeByExtension(arguments.getUrl());
            if (ContentType.VIDEO.equals(type) && file.length() > MAX_VIDEO_BYTES) {
                throw new ToolExecutor.ToolExecuteException("视频文件过大（"
                        + (file.length() / 1024 / 1024) + "MB），请截取片段后重试");
            }
            try (InputStream inputStream = new FileInputStream(file)) {
                byte[] bytes = inputStream.readAllBytes();
                if (ContentType.IMAGE.equals(type)) {
                    MultipartScaleImageHelper helper = new MultipartScaleImageHelper(bytes);
                    byte[] afterScale = helper.scaleImage(settings.getMaxLength());
                    return ScaleImageHelper.byteArrayToBase64(afterScale);
                }
                // 视频等不做图像缩放，按真实 MIME 编码
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
        private String captureType;
    }

    @Data
    @Accessors
    public static class Settings{
        private Integer maxLength;
        public Settings(){
            this.maxLength = DEFAULT_IMAGE_LENGTH;
        }
        public Settings(Integer maxLength){
            this.maxLength = maxLength;
        }
    }
}