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
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.extension.ExtensionScriptMeta;
import com.fishsunny.assistant.engine.tool.extension.ExtensionScriptService;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(ExtensionScriptTool.class);

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
        Path tempFile = null;
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getScriptName())) {
                throw new ToolExecutor.ToolExecuteException("参数 scriptName 不能为空");
            }

            // 1. 查找脚本
            ExtensionScriptMeta script = extensionScriptService.findScript(arguments.getScriptName());
            if (script == null) {
                List<ExtensionScriptMeta> available = extensionScriptService.getAvailableScripts();
                StringBuilder names = new StringBuilder();
                for (ExtensionScriptMeta meta : available) {
                    names.append(meta.getName()).append(", ");
                }
                throw new ToolExecutor.ToolExecuteException(
                        "未找到脚本 [" + arguments.getScriptName() + "]，当前可用的脚本: " + names);
            }

            // 2. 替换脚本体中的参数占位符
            String scriptBody = script.getScriptBody();
            if (arguments.getArguments() != null) {
                Map<String, Object> params = arguments.getArguments();
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    String placeholder = "{{" + entry.getKey() + "}}";
                    String value = entry.getValue() != null ? entry.getValue().toString() : "";
                    scriptBody = scriptBody.replace(placeholder, value);
                }
            }

            // 检查是否还有未替换的占位符
            Pattern placeholderPattern = Pattern.compile("\\{\\{[^}]+}}");
            StringBuilder errorMessage = new StringBuilder();
            Matcher matcher = placeholderPattern.matcher(scriptBody);
            while (matcher.find()) {
                errorMessage.append("未替换的参数占位符：").append(matcher.group()).append("\n");
            }
            if (StringUtils.hasText(errorMessage)) {
                throw new ToolExecutor.ToolExecuteException(errorMessage.toString());
            }

            // 3. 写入临时文件并构建执行器
            String type = script.getType() != null ? script.getType().toLowerCase() : "cmd";
            tempFile = extensionScriptService.writeTempScript(scriptBody, type);
            ProcessBuilder processBuilder = buildProcess(type, tempFile);

            // 4. 执行
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() -> {
                byte[] bytes = process.getInputStream().readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            });

            String result;
            try {
                result = future.get(settings.getTimeout(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                throw new ToolExecutor.ToolExecuteException(
                        "脚本执行超时（" + settings.getTimeout() + "秒）: " + script.getName());
            } finally {
                executor.shutdownNow();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ToolExecutor.ToolExecuteException(
                        "脚本执行失败，退出码：" + exitCode + "，输出：" + result);
            }

            // 5. 输出大小限制
            Long maxSize = settings.getMaxOutputSize();
            if (maxSize != null && maxSize > 0) {
                long outputSize = result.getBytes(StandardCharsets.UTF_8).length;
                if (outputSize > maxSize) {
                    throw new ToolExecutor.ToolExecuteException(
                            "脚本输出大小（" + ToolKit.formatSize(outputSize) + "）超过最大限制（"
                            + ToolKit.formatSize(maxSize) + "），已拒绝返回。"
                            + "请修改脚本以减少输出量。");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), result);

        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("扩展脚本执行异常: " + e.getMessage());
        } finally {
            extensionScriptService.deleteTempScript(tempFile);
        }
    }

    /** 当前操作系统是否为 Windows */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * 根据脚本类型构建 ProcessBuilder，通过临时文件执行。
     * 自动适配 Windows/Linux 平台差异：
     * - cmd: Windows 使用 cmd.exe，Linux 使用 bash
     * - powershell: Windows 使用 powershell.exe，Linux 使用 pwsh
     * - python: Windows 使用 python，Linux 使用 python3
     */
    private ProcessBuilder buildProcess(String type, Path tempFile) throws ToolExecutor.ToolExecuteException {
        return switch (type) {
            case "cmd" -> IS_WINDOWS
                    ? new ProcessBuilder("cmd.exe", "/c", tempFile.toString())
                    : new ProcessBuilder("bash", tempFile.toString());
            case "powershell" -> IS_WINDOWS
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", tempFile.toString())
                    : new ProcessBuilder("pwsh", "-NoProfile", "-File", tempFile.toString());
            case "python" -> IS_WINDOWS
                    ? new ProcessBuilder("python", tempFile.toString())
                    : new ProcessBuilder("python3", tempFile.toString());
            case "bash" -> new ProcessBuilder("bash", tempFile.toString());
            default -> throw new ToolExecutor.ToolExecuteException(
                    "不支持的脚本类型: " + type + "，支持的类型: cmd, powershell, python, bash");
        };
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        List<ExtensionScriptMeta> scripts = extensionScriptService.getAvailableScripts();
        StringBuilder desc = new StringBuilder("执行扩展脚本。");
        if (!scripts.isEmpty()) {
            desc.append(" 当前可用的脚本: ");
            desc.append(scripts.stream()
                    .map(s -> {
                        StringBuilder sb = new StringBuilder(s.getName());
                        if (s.getDescription() != null && !s.getDescription().isEmpty()) {
                            sb.append("（").append(s.getDescription()).append("）");
                        }
                        return sb.toString();
                    })
                    .collect(Collectors.joining("; ")));
        } else {
            desc.append(" 当前无可用脚本。");
        }
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
                                + "脚本中使用 {{参数名}} 引用这些值。如果脚本无需参数，可省略此字段。")
                ));
    }

    @Data
    private static class Arguments {
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
