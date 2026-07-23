package com.fishsunny.assistant.engine.tool.framwork;

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

    protected final boolean enable;

    public ToolKit(List<ToolHandler> tools, boolean enable) {
        this.enable = enable;
        register(tools);
    }

    protected static final Logger logger = LoggerFactory.getLogger(ToolKit.class);

    protected final Map<String, ToolHandler> instanceMap = new HashMap<>();

    public ToolHandler getTool(String name) {
        return instanceMap.get(name);
    }

    public List<ToolHandler> getTools() {
        if (!enable) {
            return List.of();
        }
        return List.of(instanceMap.values().toArray(new ToolHandler[0]));
    }

    protected void register(List<ToolHandler> tools) {
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
