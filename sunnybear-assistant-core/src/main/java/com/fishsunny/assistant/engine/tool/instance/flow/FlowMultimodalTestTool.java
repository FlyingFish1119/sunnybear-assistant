package com.fishsunny.assistant.engine.tool.instance.flow;

/*
 * @Usage 多模态流程测试工具 - 读取一张图片（本地路径或 http(s) URL），
 *        把图片作为 image 多模态内容放进工具回复消息（多模态数组），
 *        用于测试后续轮次中 AI 能否读取到工具返回的图片信息。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/2
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.FlowToolKit;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ToolKitComponent(FlowToolKit.class)
@ConditionalOnExpression("${engine.tool.flow.enable:true} && ${engine.tool.flow.multimodal-test.enable:true}")
public class FlowMultimodalTestTool implements ToolHandler, MultimodalResultAble {

    @Value("${assistant.file.base-path:data/}")
    private String basePath;

    public static final String NAME = "flow_multimodal_test_tool";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public FlowMultimodalTestTool(ObjectMapper objectMapper) {
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        测试工具：读取一张图片（本地绝对路径或 http(s) 网络 URL），
                        将图片作为 image 多模态内容放进工具回复消息的多模态数组，供后续轮次中 AI 读取。
                        用于验证 AI 能否读取到工具返回的图片信息。
                        注：并不是所有协议都支持Tool的多模态内容返回，此工具可以验证协议是否支持。
                        """)
                .setRequired(List.of("url"));
        ToolRegister.Parameters parameter = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("图片地址：本地图片的绝对路径");

        register.setParameters(List.of(parameter));

        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            if (!(context.get("chatSession") instanceof ChatSession chatSession)) {
                throw new ToolExecutor.ToolExecuteException("工具内部错误导致此工具不可使用，原因: chatSession 依赖缺失");
            }

            LocalDateTime startTime = LocalDateTime.now();
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            String url = arguments.getUrl();
            if (!StringUtils.hasText(url)) {
                throw new ToolExecutor.ToolExecuteException("参数 url 不能为空");
            }

            Path imagePath = chatSession.buildSessionFilePath(basePath).resolve(UUID.randomUUID() + ".png");

            // 读取图片字节
            byte[] imageBytes = readImage(url);
            MultipartScaleImageHelper helper = new MultipartScaleImageHelper(imageBytes);
            byte[] bytes = helper.scaleImage(1024);
            String base64 = ScaleImageHelper.byteArrayToBase64(bytes);

            String result = """
                    ```flow
                    工具[flow_multimodal_test_tool]读取图片**成功**。
                    [开始时间]：${startTime}
                    [图片地址]：${url}
                    [图片大小]：${size} 字节
                    [保存地址]：${imagePath}
                    [说明]：已把图片作为 image 多模态内容放入回复消息多模态数组，后续轮次中 AI 应能读取到该图片信息。
                    ```
                    """.replace("${startTime}", startTime.format(DATE_TIME_FORMATTER))
                    .replace("${url}", url)
                    .replace("${size}", String.valueOf(imageBytes.length))
                    .replace("${imagePath}", imagePath.toString());
            return new ToolExecutor.ToolExecuteResponse(name(), result)
                    .modalContent(imagePath.toString(), ContentTypeVariable.IMAGE, base64);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        }
    }

    private byte[] readImage(String url) throws Exception {
        File file = new File(url);
        if (!file.exists() || !file.isFile()) {
            throw new ToolExecutor.ToolExecuteException("本地图片文件不存在：" + url);
        }
        return Files.readAllBytes(file.toPath());
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
    private static class Arguments {
        private String url;
    }
}
