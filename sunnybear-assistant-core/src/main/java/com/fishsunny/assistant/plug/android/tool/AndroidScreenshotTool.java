package com.fishsunny.assistant.plug.android.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
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

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.screenshot.enable:true}")
public class AndroidScreenshotTool implements ToolHandler {

    public static final String NAME = "android_screenshot";

    private final AISettings aiSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;

    public AndroidScreenshotTool(@Qualifier(AISettings.OCR) AISettings aiSettings,
                                 ChatHttpHandler chatHttpHandler,
                                 ObjectMapper objectMapper,
                                 AndroidBridgeService bridgeService) {
        this.aiSettings = aiSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("截取屏幕并返回中文描述。⚠️ 优先用 android_get_ui_tree 获取结构化内容，截图仅用于视觉确认（颜色、图标、图片）。")
                .setRequired(List.of())
                .setParameters(List.of(
                        param("target", "string", "（可选）描述你希望重点关注的页面区域或内容。例如 '顶部导航栏的结构'、'搜索结果列表的第几条'。不填则对页面进行全面描述。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);

            // 1. 截取 Android 设备屏幕
            String imageBase64 = bridgeService.sendCommand("screenshot", "{}");
            if (imageBase64 == null || imageBase64.startsWith("错误") || imageBase64.startsWith("截图失败")) {
                throw new ToolExecutor.ToolExecuteException(imageBase64);
            }

            // 2. 构建提示词，发送给视觉模型分析
            String target = args.getTarget();
            String prompt;
            if (StringUtils.hasText(target)) {
                prompt = "请用中文描述当前 Android 设备屏幕内容，重点聚焦以下目标区域："
                        + target + "\n请详细描述该区域的内容、布局和可用操作。";
            } else {
                prompt = "请用中文描述当前 Android 设备屏幕的完整内容，"
                        + "包括界面布局、可见文字、按钮和可操作元素。";
            }

            // 3. 发送给 AI 视觉模型分析
            AtomicReference<String> caption = new AtomicReference<>("");
            ChatRequest request = new ChatRequest()
                    .loadSettings(aiSettings)
                    .setMessages(List.of(
                            new ChatMessage().system(SystemPrompts.OCR),
                            new ChatMessage().user(prompt, imageBase64)
                    ));
            chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request,
                    aiSettings.getStream() != null ? aiSettings.getStream() : true,
                    null,
                    (result, lastRes) -> caption.set(result.content())
            );

            return new ToolExecutor.ToolExecuteResponse(NAME, caption.get()).setSucceed(true);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("Android 截图分析失败: " + e.getMessage());
        }
    }

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static ToolRegister.Parameters param(String name, String type, String desc) {
        return new ToolRegister.Parameters().setParameterName(name).setType(type).setDescription(desc);
    }

    @Data @Accessors(chain = true)
    private static class Arguments {
        private String target;
    }
}
