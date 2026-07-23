package com.fishsunny.assistant.utils;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 06:25
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fishsunny.assistant.variable.ContentTypeVariable;

import java.util.*;

public class ObjectUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static <T> T getLast(T[] array) {
        if (array == null) {
            throw new IllegalArgumentException("array is null");
        }
        if (array.length == 0) {
            return null;
        }
        return array[array.length - 1];
    }

    public static <T> T getLast(List<T> list) {
        if (list == null) {
            throw new IllegalArgumentException("list is null");
        }
        if (list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    public static <T> T removeLast(List<T> list) {
        if (list == null) {
            throw new IllegalArgumentException("list is null");
        }
        if (list.isEmpty()) {
            return null;
        }
        return list.remove(list.size() - 1);
    }

    public static <T> List<T> cloneList(List<T> list, Class<T> cls) {
        if (list == null) {
            throw new IllegalArgumentException("list is null");
        }

        JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(ArrayList.class, cls);

        try {
            objectMapper.writeValueAsString(list);
            return objectMapper.readValue(objectMapper.writeValueAsString(list), listType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T cloneObject(T obj) {
        if (obj == null) {
            throw new IllegalArgumentException("obj is null");
        }
        try {
            return (T) objectMapper.readValue(objectMapper.writeValueAsString(obj), obj.getClass());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 文件类型后缀映射 ====================

    /** 后缀 → ContentType 映射表，需要扩展时直接往这里加即可 */
    private static final Map<String, String> EXTENSION_TYPE_MAP = new HashMap<>();

    /** 后缀 → MIME 类型映射表 */
    private static final Map<String, String> EXTENSION_MIME_MAP = new HashMap<>();

    static {
        // 图片
        putExtensions(ContentTypeVariable.IMAGE,
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tiff", "tif", "heic", "heif");
        putMimeTypes("image/png", "png");
        putMimeTypes("image/jpeg", "jpg", "jpeg");
        putMimeTypes("image/gif", "gif");
        putMimeTypes("image/webp", "webp");
        putMimeTypes("image/bmp", "bmp");
        putMimeTypes("image/svg+xml", "svg");
        putMimeTypes("image/x-icon", "ico");
        putMimeTypes("image/tiff", "tiff", "tif");
        putMimeTypes("image/heic", "heic", "heif");

        // 音频
        putExtensions(ContentTypeVariable.AUDIO,
                "mp3", "wav", "ogg", "flac", "aac", "wma", "m4a", "opus", "weba");
        putMimeTypes("audio/mpeg", "mp3");
        putMimeTypes("audio/wav", "wav");
        putMimeTypes("audio/ogg", "ogg");
        putMimeTypes("audio/flac", "flac");
        putMimeTypes("audio/aac", "aac");
        putMimeTypes("audio/x-ms-wma", "wma");
        putMimeTypes("audio/mp4", "m4a");
        putMimeTypes("audio/opus", "opus");
        putMimeTypes("audio/webm", "weba");

        // 视频
        putExtensions(ContentTypeVariable.VIDEO,
                "mp4", "webm", "avi", "mov", "wmv", "flv", "mkv", "m4v", "3gp");
        putMimeTypes("video/mp4", "mp4");
        putMimeTypes("video/webm", "webm");
        putMimeTypes("video/x-msvideo", "avi");
        putMimeTypes("video/quicktime", "mov");
        putMimeTypes("video/x-ms-wmv", "wmv");
        putMimeTypes("video/x-flv", "flv");
        putMimeTypes("video/x-matroska", "mkv");
        putMimeTypes("video/mp4", "m4v");
        putMimeTypes("video/3gpp", "3gp");

        // 文本
        putExtensions(ContentTypeVariable.TEXT,
                "txt", "md", "csv", "log", "xml", "json", "yaml", "yml",
                "html", "htm", "css", "js", "ts", "jsx", "tsx",
                "java", "py", "c", "cpp", "h", "go", "rs", "sh", "bat", "sql");
        putMimeTypes("text/plain", "txt", "log");
        putMimeTypes("text/markdown", "md");
        putMimeTypes("text/csv", "csv");
        putMimeTypes("text/xml", "xml");
        putMimeTypes("application/json", "json");
        putMimeTypes("text/yaml", "yaml", "yml");
        putMimeTypes("text/html", "html", "htm");
        putMimeTypes("text/css", "css");
        putMimeTypes("text/javascript", "js");
        putMimeTypes("text/typescript", "ts");
        putMimeTypes("text/jsx", "jsx");
        putMimeTypes("text/tsx", "tsx");
        putMimeTypes("text/x-java-source", "java");
        putMimeTypes("text/x-python", "py");
        putMimeTypes("text/x-c", "c");
        putMimeTypes("text/x-c++", "cpp");
        putMimeTypes("text/x-c", "h");
        putMimeTypes("text/x-go", "go");
        putMimeTypes("text/x-rust", "rs");
        putMimeTypes("text/x-sh", "sh");
        putMimeTypes("text/x-msdos-batch", "bat");
        putMimeTypes("text/x-sql", "sql");

        // 文档（归入 FILE）
        putExtensions(ContentTypeVariable.FILE,
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "zip", "rar", "7z", "tar", "gz");
        putMimeTypes("application/pdf", "pdf");
        putMimeTypes("application/msword", "doc");
        putMimeTypes("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        putMimeTypes("application/vnd.ms-excel", "xls");
        putMimeTypes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        putMimeTypes("application/vnd.ms-powerpoint", "ppt");
        putMimeTypes("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        putMimeTypes("application/zip", "zip");
        putMimeTypes("application/vnd.rar", "rar");
        putMimeTypes("application/x-7z-compressed", "7z");
        putMimeTypes("application/x-tar", "tar");
        putMimeTypes("application/gzip", "gz");
    }

    private static void putExtensions(String type, String... extensions) {
        for (String ext : extensions) {
            EXTENSION_TYPE_MAP.put(ext, type);
        }
    }

    private static void putMimeTypes(String mimeType, String... extensions) {
        for (String ext : extensions) {
            EXTENSION_MIME_MAP.put(ext, mimeType);
        }
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     *
     * @param extension 文件扩展名（如 "png"、"mp4"）
     * @return MIME 类型（如 "image/png"），未匹配默认返回 "application/octet-stream"
     */
    public static String getMimeTypeByExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "application/octet-stream";
        }
        return EXTENSION_MIME_MAP.getOrDefault(extension.toLowerCase(), "application/octet-stream");
    }

    /**
     * 运行时动态注册后缀映射，方便扩展而不改静态块
     *
     * @param type       类型常量 {@link ContentTypeVariable}
     * @param extensions 要注册的后缀
     */
    public static void registerExtension(String type, String... extensions) {
        putExtensions(type, extensions);
    }

    /**
     * 根据文件名（或后缀）判断文件类型
     * <p>后缀匹配 → 类型常量，未匹配默认返回 {@link ContentTypeVariable#FILE}
     *
     * @param fileName 文件名，如 "photo.png"、"report.pdf"，也可直接传后缀如 "mp3"
     * @return 类型常量: IMAGE / AUDIO / VIDEO / TEXT / FILE
     */
    public static String detectFileTypeByExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return ContentTypeVariable.FILE;
        }
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : fileName.toLowerCase();
        return EXTENSION_TYPE_MAP.getOrDefault(ext, ContentTypeVariable.FILE);
    }

    /**
     * 从 data URI 判断文件类型，内部解析 MIME 取子类型作为后缀，委托给 {@link #detectFileTypeByExtension}
     * <p>示例: "data:image/png;base64,..." → 后缀 "png" → IMAGE
     *
     * @param dataUri data URI 字符串
     * @return 类型常量: IMAGE / AUDIO / VIDEO / TEXT / FILE
     */
    public static String detectFileType(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return ContentTypeVariable.FILE;
        }
        // data:image/png;base64,...  → 取 image/png
        int colonIdx = dataUri.indexOf(':');
        int semicolonIdx = dataUri.indexOf(';');
        if (colonIdx < 0 || semicolonIdx <= colonIdx) {
            return ContentTypeVariable.FILE;
        }
        String mimeType = dataUri.substring(colonIdx + 1, semicolonIdx);
        // image/png → png,  text/plain → txt, application/pdf → pdf
        String subType = mimeType.contains("/")
                ? mimeType.substring(mimeType.indexOf('/') + 1)
                : mimeType;
        // svg+xml → svg
        if (subType.contains("+")) {
            subType = subType.substring(0, subType.indexOf('+'));
        }
        return detectFileTypeByExtension(subType);
    }

    public static String encodeToDataUrl(String filePath, byte[] data) {
        String ext = filePath.contains(".")
                ? filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase()
                : filePath.toLowerCase();
        String mimeType = getMimeTypeByExtension(ext);
        String base64 = Base64.getEncoder().encodeToString(data);
        return "data:" + mimeType + ";base64," + base64;
    }
}
