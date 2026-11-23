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
import com.fishsunny.assistant.engine.tool.framework.*;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import com.fishsunny.assistant.engine.tool.instance.os.executor.LocalScriptExecutor;
import com.fishsunny.assistant.engine.tool.instance.os.executor.SatelliteScriptExecutor;
import com.fishsunny.assistant.engine.tool.instance.os.executor.ScriptExecutor;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptMeta;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptService;
import com.fishsunny.assistant.satellite.service.SatelliteBridgeService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 扩展脚本工具 —— 执行 tool-extension/ 目录下的扩展脚本。
 * <p>
 * 脚本为 .yaml/.yml 格式，包含元数据（name、description、type、parameters、script），
 * 脚本体中可用 {{paramName}} 引用参数。
 * </p>
 * <p>
 * 只负责脚本查找与执行路由：本机执行细节归 {@link LocalScriptExecutor}，
 * 卫星机执行细节归 {@link SatelliteScriptExecutor}。
 * </p>
 */
@ToolKitComponent(OSToolKit.class)
@ConditionalOnExpression("${engine.tool.os.enable:true} && ${engine.tool.os.extension-script.enable:true}")
public class ExtensionScriptTool implements ToolHandler {

    public static final String NAME = "extension_script_tool";
    public static final String SETTINGS = "extension_script_tool_settings";

    /** 默认脚本执行超时时间（秒）：Settings 默认值与卫星机等待窗口兜底共用 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /** 默认最大输出大小限制（字节） */
    private static final long DEFAULT_MAX_OUTPUT_SIZE = 65536L;

    private final ObjectMapper objectMapper;
    private final ExtensionScriptService extensionScriptService;
    private final Settings settings;
    private final SatelliteBridgeService satelliteBridgeService;
    private final LocalScriptExecutor localScriptExecutor;
    private final SatelliteScriptExecutor satelliteScriptExecutor;

    public ExtensionScriptTool(ObjectMapper objectMapper,
                               ExtensionScriptService extensionScriptService,
                               @Qualifier(SETTINGS) Settings settings,
                               SatelliteBridgeService satelliteBridgeService,
                               LocalScriptExecutor localScriptExecutor,
                               SatelliteScriptExecutor satelliteScriptExecutor) {
        this.objectMapper = objectMapper;
        this.extensionScriptService = extensionScriptService;
        this.settings = settings;
        this.satelliteBridgeService = satelliteBridgeService;
        this.localScriptExecutor = localScriptExecutor;
        this.satelliteScriptExecutor = satelliteScriptExecutor;
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getScriptName())) {
                throw new ToolExecutor.ToolExecuteException("参数 scriptName 不能为空");
            }

            ExtensionScriptMeta meta = extensionScriptService.requireScript(arguments.getScriptName());
            Map<String, Object> params = arguments.getArguments() == null ? Map.of() : arguments.getArguments();

            // ===== 执行分流：调用方指定 device 时经桥接在该卫星机上执行，本机路径不受影响 =====
            ChatSession chatSession = (ChatSession) context.get("chatSession");
            ScriptExecutor executor = StringUtils.hasText(arguments.getDevice())
                    ? satelliteScriptExecutor
                    : localScriptExecutor;
            String result = executor.execute(meta, params,
                    Boolean.TRUE.equals(arguments.getBackground()), arguments.getDevice(), chatSession.getId());
            return new ToolExecutor.ToolExecuteResponse(name(), result);

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
        StringBuilder desc = new StringBuilder("执行扩展脚本。转入后台时会返回一个后台任务 ID，可用 wait_flow_tool 的 eventId 等它结束。");
        desc.append(extensionScriptService.buildScriptSection());
        desc.append(" 指定 device 时脚本在该卫星机上按声明的脚本类型执行，缺省在本机执行。");

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
                                "设为 true 后台执行（无超时限制），适合长时间任务。输出写入日志文件；返回后台任务 ID 可供 wait_flow_tool 的 eventId 等待。"),
                        new ToolRegister.Parameters("device", "string",
                                "在指定卫星机上执行，填卫星机 deviceId；缺省在本机执行。当前在线："
                                        + satelliteBridgeService.onlineDevices())
                ));
    }

    @Data
    private static class Arguments {
        private Boolean background;
        private String scriptName;
        private Map<String, Object> arguments;
        /** 卫星机 deviceId：非空时脚本经桥接在该卫星机上执行 */
        private String device;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** 脚本执行超时时间，单位秒 */
        private Long timeout;
        /** 输出字节数上限（UTF-8 编码），超出部分落盘到会话文件 */
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
