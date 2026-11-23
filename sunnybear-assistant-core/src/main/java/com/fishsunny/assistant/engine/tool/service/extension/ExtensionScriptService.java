package com.fishsunny.assistant.engine.tool.service.extension;

/*
 * @Usage 扩展脚本服务：扫描 tool-extension/ 目录，解析脚本元数据，构建注入描述，渲染脚本体
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描 tool-extension/ 目录下的 .yaml/.yml 脚本文件，
 * 解析元数据并提供给 ChatProcessor（描述注入）和 ExtensionScriptTool（脚本执行路由）。
 * <p>
 * 只做脚本元数据管理与准备：查找、校验、提示词构建、脚本体渲染与临时脚本文件读写；
 * 进程执行（起进程、超时转后台、收尾投递）在
 * {@code com.fishsunny.assistant.engine.tool.instance.os.executor} 的各执行器里。
 * </p>
 */
@Service
public class ExtensionScriptService {

    private static final Logger log = LoggerFactory.getLogger(ExtensionScriptService.class);

    /** 临时脚本存放子目录 */
    private static final String TEMP_DIR = "temp";

    @Value("${engine.tool.extension.dir:tool-extension/}")
    private String extensionDir;

    /** 脚本的展示标题：名称 + 类型，命令面板与回投文案共用同一套写法 */
    public static String scriptTitle(ExtensionScriptMeta script) {
        return script.getName() + " (" + script.getType() + ")";
    }

    /**
     * 查找脚本，找不到时抛出列出当前可用脚本的异常。
     *
     * @param name 脚本名称
     * @return 脚本元数据
     */
    public ExtensionScriptMeta requireScript(String name) {
        ExtensionScriptMeta script = findScript(name);
        if (script == null) {
            List<ExtensionScriptMeta> available = getAvailableScripts();
            StringBuilder names = new StringBuilder();
            for (ExtensionScriptMeta meta : available) {
                names.append(meta.getName()).append(", ");
            }
            throw new RuntimeException("未找到脚本 [" + name + "]，当前可用的脚本: " + names);
        }
        return script;
    }

    /**
     * 渲染脚本体：替换已传参数占位符、移除未传可选参数占位符、残留占位符抛错。
     * <p>
     * 本机路径与卫星机路径（经桥接下发前）共用同一套渲染规则。
     */
    public String renderScriptBody(ExtensionScriptMeta script, Map<String, Object> arguments)
            throws ToolExecutor.ToolExecuteException {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String scriptBody = script.getScriptBody();
        for (Map.Entry<String, Object> entry : safeArguments.entrySet()) {
            List<ExtensionScriptMeta.Parameter> parameters = script.getParameters();
            ExtensionScriptMeta.Parameter.validateParameters(parameters, entry);
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            scriptBody = scriptBody.replace(placeholder, value);
        }

        // 未传入的可选参数（required=false）：直接移除其占位符，不再触发未替换检查
        for (ExtensionScriptMeta.Parameter param : script.getParameters()) {
            if (!param.isRequired() && !safeArguments.containsKey(param.getName())) {
                scriptBody = scriptBody.replace("{{" + param.getName() + "}}", "");
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
        return scriptBody;
    }

    /**
     * 获取所有可用的扩展脚本（每次调用都会重新扫描目录）。
     */
    public List<ExtensionScriptMeta> getAvailableScripts() {
        return scanScripts();
    }

    /**
     * 按名称查找脚本。
     *
     * @param name 脚本名称
     * @return 脚本元数据，未找到返回 null
     */
    public ExtensionScriptMeta findScript(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        for (ExtensionScriptMeta script : getAvailableScripts()) {
            if (script.getName().equals(name)) {
                return script;
            }
        }
        return null;
    }

    /**
     * 构建用于注入到系统提示词中的脚本描述段落。
     *
     * @return 格式化的脚本描述字符串，如果没有可用脚本则返回空字符串
     */
    public String buildScriptSection() {
        List<ExtensionScriptMeta> scripts = getAvailableScripts();
        if (scripts.isEmpty()) {
            return "当前无可用脚本";
        }

        StringBuilder sb = new StringBuilder("\n[tool_extension_scripts]\n");
        sb.append("以下是可通过 extension_script_tool 调用的扩展脚本列表。");
        sb.append("调用时使用 scriptName 参数指定脚本名称，使用 arguments 参数（JSON 对象）传递脚本参数：\n");

        for (int i = 0; i < scripts.size(); i++) {
            ExtensionScriptMeta script = scripts.get(i);
            sb.append("\n").append(i + 1).append(". ").append(script.getName());
            if (script.getDescription() != null && !script.getDescription().isEmpty()) {
                sb.append(" - ").append(script.getDescription());
            }
            sb.append("\n   类型: ").append(script.getType());
            if (script.getParameters() != null && !script.getParameters().isEmpty()) {
                sb.append("\n   参数:");
                for (ExtensionScriptMeta.Parameter param : script.getParameters()) {
                    sb.append("\n     - ").append(param.toDescription());
                }
            } else {
                sb.append("\n   参数: 无");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 扫描 extensionDir 目录，解析所有 .yaml/.yml 文件。
     */
    private List<ExtensionScriptMeta> scanScripts() {
        List<ExtensionScriptMeta> scripts = new ArrayList<>();
        File dir = new File(extensionDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("扩展脚本目录不存在或不是目录: {}", dir.getAbsolutePath());
            return scripts;
        }

        scanDirectory(dir, scripts);
        return scripts;
    }

    /**
     * 递归扫描目录，查找 .yaml/.yml 文件并解析。
     */
    private void scanDirectory(File dir, List<ExtensionScriptMeta> scripts) {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file, scripts);
            } else if (file.getName().endsWith(".yaml") || file.getName().endsWith(".yml")) {
                try {
                    ExtensionScriptMeta meta = ExtensionScriptMeta.fromYaml(file);
                    scripts.add(meta);
                    log.debug("解析扩展脚本成功: {} -> {}", file.getPath(), meta.getName());
                } catch (Exception e) {
                    log.warn("解析扩展脚本失败，跳过: {}，原因: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }

    /**
     * 将脚本内容写入临时文件（位于 extensionDir/temp/ 下），文件名使用 UUID。
     *
     * @param scriptBody 脚本内容
     * @param type       脚本类型（cmd / powershell / python / bash）
     * @return 临时文件路径
     * @throws IOException 写入失败时抛出
     */
    public Path writeTempScript(String scriptBody, String type) throws IOException {
        String ext = switch (type.toLowerCase()) {
            case "cmd" -> ".bat";
            case "powershell" -> ".ps1";
            case "python" -> ".py";
            case "bash" -> ".sh";
            default -> ".tmp";
        };

        File tempDir = new File(extensionDir, TEMP_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            log.warn("创建临时目录失败: {}", tempDir.getAbsolutePath());
        }

        String fileName = UUID.randomUUID() + ext;
        Path tempFile = new File(tempDir, fileName).toPath();
        Files.writeString(tempFile, scriptBody, StandardCharsets.UTF_8);
        log.debug("写入临时脚本文件: {}", tempFile);
        return tempFile;
    }

    /**
     * 删除临时脚本文件。失败时仅记录警告，不抛异常。
     *
     * @param path 临时文件路径
     */
    public void deleteTempScript(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
                log.debug("删除临时脚本文件: {}", path);
            } catch (IOException e) {
                log.warn("删除临时脚本文件失败: {}", path, e);
            }
        }
    }
}
