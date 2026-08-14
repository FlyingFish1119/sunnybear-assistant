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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(AndroidToolKit.class)
@ConditionalOnExpression("${plug.android.tool.enable:true} && ${plug.android.tool.ui_tree.enable:true}")
public class AndroidGetUiTreeTool implements ToolHandler {

    public static final String NAME = "android_get_ui_tree";

    private static final String MODE_FULL = "full";
    private static final String MODE_ELEMENT = "element";

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final AndroidBridgeService bridgeService;
    private final AISettings taskAISettings;
    private final ChatHttpHandler chatHttpHandler;

    public AndroidGetUiTreeTool(ObjectMapper objectMapper,
                                AndroidBridgeService bridgeService,
                                @Qualifier(AISettings.TASK) AISettings taskAISettings,
                                ChatHttpHandler chatHttpHandler) {
        this.objectMapper = objectMapper;
        this.bridgeService = bridgeService;
        this.taskAISettings = taskAISettings;
        this.chatHttpHandler = chatHttpHandler;

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription("获取屏幕 UI 树。element 模式返回可交互元素列表（推荐），full 模式返回完整结构。分析屏幕的首选工具。")
                .setRequired(List.of());

        ToolRegister.Parameters modeParam = new ToolRegister.Parameters()
                .setParameterName("mode")
                .setType("string")
                .setDescription("读取模式，默认 'element'。"
                        + "'element' - AI 提取可交互元素，返回精简列表（按钮、输入框、文本等）；"
                        + "'full' - 返回完整 UI 树，适合需要深入分析布局时使用。");

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("（当模式为 element 时必选）在 element 模式下描述你需要的元素类型或区域，例如 '所有输入框和按钮'、'顶部导航栏'。不填则提取全部可交互元素。full 模式下忽略。");

        register.setParameters(List.of(modeParam, targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments args = objectMapper.readValue(argumentsJson, Arguments.class);

            String mode = MODE_ELEMENT;
            if (StringUtils.hasText(args.getMode())) {
                mode = args.getMode().toLowerCase().trim();
            }

            if (!MODE_ELEMENT.equals(mode) && !MODE_FULL.equals(mode)) {
                throw new ToolExecutor.ToolExecuteException(
                        "无效的 mode 参数: " + mode + "，仅支持 '" + MODE_ELEMENT + "' 或 '" + MODE_FULL + "'");
            }

            if (!StringUtils.hasText(args.getTarget()) && MODE_ELEMENT.equals(mode)) {
                throw new ToolExecutor.ToolExecuteException("mode 为 element 时，请填写 target 参数");
            }

            // 不限制深度，全量获取 UI 树，避免遗漏深层嵌套元素
            String uiTree = bridgeService.sendCommand("get_ui_tree", "{\"maxDepth\":99}");

            if (MODE_FULL.equals(mode)) {
                return new ToolExecutor.ToolExecuteResponse(NAME, uiTree);
            } else {
                return buildElementResult(uiTree, args.getTarget());
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取 UI 树失败: " + e.getMessage());
        }
    }

    private ToolExecutor.ToolExecuteResponse buildElementResult(String uiTree, String target)
            throws Exception {
        String focus = StringUtils.hasText(target)
                ? "请重点提取以下目标：" + target + "。"
                : "请提取屏幕上所有可交互元素。";

        String prompt = """
                任务: ${focus}

                要求:
                1. 列出可交互和可见元素（按钮、输入框、文本标签、开关、复选框、列表项等），标注其坐标范围。
                2. 每个元素标注：类型、可见文本/描述/hint、坐标 rect=[l,t,r,b]、是否可点击/可滚动/可编辑。
                3. 只输出有实际交互或信息价值的元素，忽略纯布局容器、装饰性元素。
                4. 输出格式如下（精简，每行一个元素）：
                   - Button "确定" rect=[100,200,300,350] clickable
                   - EditText hint="请输入手机号" rect=[50,100,400,150] editable focused
                   - TextView "欢迎回来" rect=[20,50,380,90]
                5. 输出尽量精简，不要输出分析过程或补充说明。
                6. 坐标保留原始像素值，不要做任何转换。
                """
                .replace("${focus}", focus);

        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(SystemPrompts.SUMMARY),
                        new ChatMessage().user(prompt + "\n\nUI树结构:\n```\n" + uiTree + "\n```")
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

    @Override public String name() { return NAME; }
    @Override public ToolRegister getRegister() { return register; }


    private static class Arguments {
        private String mode;
        private String target;

        public String getMode() { return mode; }
        public String getTarget() { return target; }
    }
}
