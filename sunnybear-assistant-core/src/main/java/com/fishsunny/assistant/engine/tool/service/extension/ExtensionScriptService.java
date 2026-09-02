package com.fishsunny.assistant.engine.tool.service.extension;

/*
 * @Usage 扩展脚本服务：扫描 tool-extension/ 目录，解析脚本元数据，构建注入描述
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.OSToolKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描 tool-extension/ 目录下的 .yaml/.yml 脚本文件，
 * 解析元数据并提供给 ChatProcessor（描述注入）和 ExtensionScriptTool（脚本执行）。
 */
@Service
public class ExtensionScriptService {

    private static final Logger log = LoggerFactory.getLogger(ExtensionScriptService.class);

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();

    /** 临时脚本存放子目录 */
    private static final String TEMP_DIR = "temp";

    @Value("${engine.tool.extension.dir:tool-extension/}")
    private String extensionDir;

    /** 会话文件基目录（后台日志输出用），与 CommandTool 保持一致 */
    @Value("${assistant.file.base-path:}")
    private String basePath;

    public String runScript(String name, Map<String, Object> arguments, long timeout) throws Exception {
        PreparedScript prepared = prepareScript(name, arguments);

        Process process = null;
        try {
            process = prepared.processBuilder().start();
            String result;
            if (timeout < 0) {
                result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                final Process finalProcess = process;
                Future<String> future = EXECUTOR_SERVICE.submit(() -> {
                    byte[] bytes = finalProcess.getInputStream().readAllBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                });
                try {
                    result = future.get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    process.destroyForcibly();
                    throw new ToolExecutor.ToolExecuteException(
                            "脚本执行超时（" + timeout + "秒）: " + prepared.script().getName());
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ToolExecutor.ToolExecuteException(
                        "脚本执行失败，退出码：" + exitCode + "，输出：" + result);
            }
            return result;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            deleteTempScript(prepared.tempFile());
        }
    }

    /**
     * 后台执行脚本：将脚本放入守护线程执行，输出以追加模式写入 session/file 目录。
     * <p>
     * 后台模式特点（与 CommandTool 后台执行对齐）：
     * <ul>
     *   <li>无超时限制 —— 脚本可以运行任意长时间</li>
     *   <li>无输出大小限制 —— 输出直接写入文件，不经过内存缓存</li>
     *   <li>输出实时写入 —— 使用流式读取，每读取到数据立即 flush 到文件</li>
     *   <li>返回文件路径 —— AI 可通过 session_file_tool 或 file_read_tool 查看输出</li>
     * </ul>
     * </p>
     *
     * @param name      脚本名称
     * @param arguments 脚本参数
     * @param sessionId 会话 ID，用于确定日志文件目录
     * @return 包含日志文件路径的响应
     */
    public String runScriptAsync(String name, Map<String, Object> arguments, String sessionId) throws Exception {
        // 1. 同步准备脚本（查找、参数校验、占位符替换、写临时文件），错误即时反馈
        PreparedScript prepared = prepareScript(name, arguments);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "script_" + timestamp + ".log";
        Path logDir = Paths.get(basePath, "session", sessionId, "file");
        Path logFile = logDir.resolve(fileName);

        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法创建后台输出目录 [" + logDir + "]: " + e.getMessage());
        }

        // 2. 写入日志文件头（同步）
        try (BufferedWriter headerWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            headerWriter.write("=== 后台脚本执行日志 ===");
            headerWriter.newLine();
            headerWriter.write("启动时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            headerWriter.newLine();
            headerWriter.write("脚本: " + prepared.script().getName() + " (" + prepared.script().getType() + ")");
            headerWriter.newLine();
            headerWriter.write("脚本文件: " + prepared.script().getFilePath());
            headerWriter.newLine();
            headerWriter.write("日志文件: " + logFile.toAbsolutePath());
            headerWriter.newLine();
            headerWriter.write("=".repeat(60));
            headerWriter.newLine();
            headerWriter.newLine();
            headerWriter.flush();
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("无法写入后台日志文件 [" + logFile + "]: " + e.getMessage());
        }

        // 3. 在守护线程中执行脚本，流式写入输出
        Thread thread = new Thread(() -> runScriptToFile(prepared, logFile), "script-executor-" + timestamp);
        thread.setDaemon(true);
        thread.start();

        return "脚本已在后台启动执行。\n"
                + "输出日志文件: " + logFile.toAbsolutePath() + "\n"
                + "> 提示：使用 file_read_tool 读取日志文件内容查看脚本输出。"
                + "脚本执行完成后，日志末尾会写入退出码和结束时间。";
    }

    /**
     * 查找脚本、校验并替换参数、写入临时脚本文件，返回可执行的预备结果。
     */
    private PreparedScript prepareScript(String name, Map<String, Object> arguments) throws Exception {
        log.info("Running script: {}", name);
        arguments = arguments == null ? Map.of() : arguments;

        // 1. 查找脚本
        ExtensionScriptMeta script = findScript(name);
        if (script == null) {
            List<ExtensionScriptMeta> available = getAvailableScripts();
            StringBuilder names = new StringBuilder();
            for (ExtensionScriptMeta meta : available) {
                names.append(meta.getName()).append(", ");
            }
            throw new RuntimeException("未找到脚本 [" + name + "]，当前可用的脚本: " + names);
        }

        // 2. 替换脚本体中的参数占位符
        String scriptBody = script.getScriptBody();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            List<ExtensionScriptMeta.Parameter> parameters = script.getParameters();
            ExtensionScriptMeta.Parameter.validateParameters(parameters, entry);
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            scriptBody = scriptBody.replace(placeholder, value);
        }

        // 未传入的可选参数（required=false）：直接移除其占位符，不再触发未替换检查
        for (ExtensionScriptMeta.Parameter param : script.getParameters()) {
            if (!param.isRequired() && !arguments.containsKey(param.getName())) {
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

        // 3. 写入临时脚本文件并构建进程
        Path path = writeTempScript(scriptBody, script.getType());
        ProcessBuilder processBuilder = buildProcess(script.getType(), path);
        processBuilder.redirectErrorStream(true);
        return new PreparedScript(script, path, processBuilder);
    }

    /**
     * 在后台执行已准备的脚本，将输出流式写入日志文件，结束后写入退出码和结束时间。
     */
    private void runScriptToFile(PreparedScript prepared, Path logFile) {
        Process process = null;
        try {
            process = prepared.processBuilder().start();

            OSToolKit.writeLog(logFile, process);
            appendFooter(logFile, "脚本执行完成，退出码: " + process.waitFor());
        } catch (Exception e) {
            log.error("Error executing script in background: {}", e.getMessage());
            appendFooter(logFile, "脚本执行异常: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            deleteTempScript(prepared.tempFile());
        }
    }

    /**
     * 向日志文件追加结束标记（退出码/异常信息 + 结束时间）。
     */
    private void appendFooter(Path logFile, String message) {
        try (BufferedWriter footerWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            footerWriter.newLine();
            footerWriter.write("=".repeat(60));
            footerWriter.newLine();
            footerWriter.write(message);
            footerWriter.newLine();
            footerWriter.write("结束时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            footerWriter.newLine();
            footerWriter.flush();
        } catch (IOException ignored) {
            // 无法写入结束信息，静默忽略
        }
    }

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

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
     * @param type       脚本类型（cmd / powershell / python）
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

    /**
     * 已准备的脚本：包含脚本元数据、临时脚本文件路径以及可启动的进程构建器。
     */
    private record PreparedScript(
            ExtensionScriptMeta script,
            Path tempFile,
            ProcessBuilder processBuilder
    ) {}
}
