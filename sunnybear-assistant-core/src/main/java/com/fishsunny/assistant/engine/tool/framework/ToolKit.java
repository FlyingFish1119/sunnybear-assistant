package com.fishsunny.assistant.engine.tool.framework;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class ToolKit {

    public ToolKit(List<? extends ToolHandler> tools) {
        register(tools);
    }

    protected static final Logger logger = LoggerFactory.getLogger(ToolKit.class);

    protected final Map<String, ToolHandler> instanceMap = new HashMap<>();

    public ToolHandler getTool(String name) {
        return instanceMap.get(name);
    }

    public List<ToolHandler> getTools() {
        return List.of(instanceMap.values().toArray(new ToolHandler[0]));
    }

    // ==================== 元信息 & 可见性声明 ====================

    /**
     * 工具集展示名（设置页用），默认取类名去掉 ToolKit 后缀。
     * 内置工具集都覆写成中文名；插件自带的工具集不覆写也能正常显示，不会空着。
     */
    public String displayName() {
        String simpleName = this.getClass().getSimpleName();
        return simpleName.endsWith("ToolKit")
                ? simpleName.substring(0, simpleName.length() - "ToolKit".length())
                : simpleName;
    }

    /** 工具集描述（设置页用），默认空串 */
    public String description() {
        return "";
    }

    /**
     * 代码层面的默认值：本工具集是否默认不对主对话开放。
     * <p>
     * 用于那些只该由子 Agent 路由或角色对话使用的工具集（角色骰子/战斗/词条/SQL、
     * ComfyUI 原子工具、decode 等）。这只是<b>默认值</b>——用户在设置页显式配置后以用户配置为准，
     * 即「设置了就开」，这里不做风险拦截。
     *
     * @return true 表示默认排除；用户未配置过该工具集时按此生效
     */
    public boolean excludeFromMainAgent() {
        return false;
    }

    protected void register(List<? extends ToolHandler> tools) {
        for (ToolHandler tool : tools) {
            ToolKitComponent annotation = AnnotationUtils.findAnnotation(tool.getClass(), ToolKitComponent.class);
            if (annotation == null) {
                logger.warn("{} 没有使用 @ToolKitComponent 注解", tool.getClass().getName());
                continue;
            }
            Class<? extends ToolKit>[] value = annotation.value();
            for (Class<? extends ToolKit> toolPackage : value) {
                if (this.getClass().getName().equals(toolPackage.getName())) {
                    instanceMap.put(tool.name(), tool);
                }
            }
        }
    }

    // ==================== 通用工具方法 ====================

    private static final int BYTES_PER_KB = 1024;

    /**
     * 格式化文件大小为人类可读形式。
     *
     * @param bytes 文件字节数
     * @return 人类可读的大小字符串，例如 1.50 KB、3.20 MB
     */
    public static String formatSize(long bytes) {
        if (bytes < BYTES_PER_KB) {
            return bytes + " B";
        }
        if (bytes < BYTES_PER_KB * BYTES_PER_KB) {
            return String.format("%.2f KB", bytes / (double) BYTES_PER_KB);
        }
        if (bytes < BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB) {
            return String.format("%.2f MB", bytes / ((double) BYTES_PER_KB * BYTES_PER_KB));
        }
        return String.format("%.2f GB", bytes / ((double) BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB));
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * 格式化文件时间为可读字符串。
     *
     * @param fileTime 文件时间
     * @return 格式化的时间字符串，fileTime 为 null 时返回"未知"
     */
    public static String formatTime(FileTime fileTime) {
        if (fileTime == null || fileTime.toMillis() == 0) {
            return "未知";
        }
        return FORMATTER.format(Instant.ofEpochMilli(fileTime.toMillis()));
    }

    /**
     * 根据文件扩展名推断编程语言标识，用于 Markdown 代码块的高亮显示。
     *
     * @param filePath 文件路径
     * @return 语言标识字符串，无法识别时返回空字符串
     */
    public static String inferLanguage(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dotIndex + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "py" -> "python";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "html", "vue" -> "html";
            case "css" -> "css";
            case "xml" -> "xml";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "md" -> "markdown";
            case "sql" -> "sql";
            case "sh", "bash" -> "bash";
            case "bat", "cmd" -> "batch";
            case "ps1" -> "powershell";
            case "c" -> "c";
            case "cpp", "cc", "cxx" -> "cpp";
            case "h", "hpp" -> "cpp";
            case "go" -> "go";
            case "rs" -> "rust";
            case "kt", "kts" -> "kotlin";
            case "scala" -> "scala";
            case "groovy" -> "groovy";
            case "php" -> "php";
            case "rb" -> "ruby";
            case "lua" -> "lua";
            case "r" -> "r";
            case "swift" -> "swift";
            case "dart" -> "dart";
            case "txt", "log" -> "";
            case "properties", "ini", "cfg", "conf" -> "ini";
            case "toml" -> "toml";
            case "gradle" -> "groovy";
            case "jsx" -> "jsx";
            case "tsx" -> "tsx";
            default -> ext;
        };
    }

}
