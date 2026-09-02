package com.fishsunny.assistant.engine.tool.instance.os;

/*
 * @Usage 扩展脚本工具：执行 tool-extension/ 目录下的脚本文件
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 扩展脚本工具 —— 执行 tool-extension/ 目录下的扩展脚本。
 * <p>
 * 脚本为 .yaml/.yml 格式，包含元数据（name、description、type、parameters、script），
 * 脚本体中可用 {{paramName}} 引用参数。
 * </p>
 */
@ToolKitComponent(OSToolKit.class)
@ConditionalOnExpression("${engine.tool.os.enable:true} && ${engine.tool.os.extension-script.enable:true}")
public class ExtensionScriptTool implements ToolHandler {

    public static final String NAME = "extension_script_tool";
    public static final String SETTINGS = "extension_script_tool_settings";

    /** 默认脚本执行超时时间（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /** 默认最大输出大小限制（字节） */
    private static final long DEFAULT_MAX_OUTPUT_SIZE = 65536L;

    private final ObjectMapper objectMapper;
    private final ExtensionScriptService extensionScriptService;
    private final Settings settings;

    public ExtensionScriptTool(ObjectMapper objectMapper,
                               ExtensionScriptService extensionScriptService,
                               @Qualifier(SETTINGS) Settings settings) {
        this.objectMapper = objectMapper;
        this.extensionScriptService = extensionScriptService;
        this.settings = settings;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getScriptName())) {
                throw new ToolExecutor.ToolExecuteException("参数 scriptName 不能为空");
            }

            String result;
            Map<String, Object> params = arguments.getArguments() == null ? Map.of() : arguments.getArguments();
            if (Boolean.TRUE.equals(arguments.getBackground())) {
                if (!(context.get("chatSession") instanceof ChatSession chatSession)) {
                    throw new ToolExecutor.ToolExecuteException("工具内部错误导致此工具不可用，原因: chatSession 依赖缺失");
                }
                result = extensionScriptService.runScriptAsync(arguments.getScriptName(), params, chatSession.getId());
            } else {
                result = extensionScriptService.runScript(arguments.getScriptName(), params, settings.getTimeout());
            }

            // 5. 输出大小限制
            Long maxSize = settings.getMaxOutputSize();
            if (maxSize != null && maxSize > 0) {
                long outputSize = result.getBytes(StandardCharsets.UTF_8).length;
                if (outputSize > maxSize) {
                    throw new ToolExecutor.ToolExecuteException(
                            "脚本输出大小（" + ToolKit.formatSize(outputSize) + "）超过最大限制（"
                            + ToolKit.formatSize(maxSize) + "），执行完毕但拒绝返回结果。"
                            + "请修改脚本以减少输出量。");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), result);

        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("扩展脚本执行异常: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        StringBuilder desc = new StringBuilder("执行扩展脚本。");
        desc.append(extensionScriptService.buildScriptSection());

        // 输出限制
        Long maxSize = settings.getMaxOutputSize();
        if (maxSize != null && maxSize > 0) {
            desc.append(" 脚本最大输出限制为 ").append(ToolKit.formatSize(maxSize)).append("。");
        }
        return new ToolRegister()
                .setName(NAME)
                .setDescription(desc.toString())
                .setRequired(List.of("scriptName"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("scriptName", "string",
                                "要执行的脚本名称，对应 tool-extension/ 目录下脚本文件的 name 字段"),
                        new ToolRegister.Parameters("arguments", "object",
                                "传递给脚本的参数，JSON 对象格式。键为参数名，值为参数值。"
                                + "脚本中使用 {{参数名}} 引用这些值。如果脚本无需参数，可省略此字段。"),
                        new ToolRegister.Parameters("background", "boolean",
                                "设为 true 后台执行（无超时限制），适合长时间任务。输出写入日志文件。")
                ));
    }

    @Data
    private static class Arguments {
        private Boolean background;
        private String scriptName;
        private Map<String, Object> arguments;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** 脚本执行超时时间，单位秒 */
        private Long timeout;
        /** 输出字节数超过此值则直接拒绝执行 */
        private Long maxOutputSize;

        public Settings() {
            this.timeout = DEFAULT_TIMEOUT_SECONDS;
            this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        }

        public Settings(Long timeout, Long maxOutputSize) {
            this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT_SECONDS;
            this.maxOutputSize = maxOutputSize != null ? maxOutputSize : DEFAULT_MAX_OUTPUT_SIZE;
        }
    }
}
