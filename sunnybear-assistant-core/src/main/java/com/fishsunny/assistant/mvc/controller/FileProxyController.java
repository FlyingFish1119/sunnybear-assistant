package com.fishsunny.assistant.mvc.controller;

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地文件代理控制器
 * <p>
 * 将本地文件通过 HTTP 提供给浏览器，用于展示图片、音视频等本地文件。
 * <p>
 * 另外提供「本机文件」的预览与打开：浏览器出于安全禁止 http 页面访问 {@code file://}，
 * 所以前端点本地文件链接时改为走这里 —— 文本类读回来做内嵌预览（{@code /file/local/preview}），
 * 其余或预览失败时交给系统默认程序打开（{@code /file/local/open}）。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/12
 */
@RestController
@RequestMapping("/file")
public class FileProxyController {

    private static final Logger log = LoggerFactory.getLogger(FileProxyController.class);

    /** 代理文件缓存最大存活时间（秒） */
    private static final int PROXY_CACHE_MAX_AGE = 3600;

    /** 文本预览大小上限：超过就不往页面塞，改交系统打开 */
    private static final long MAX_PREVIEW_BYTES = 2 * 1024 * 1024;

    /** 可直接当文本预览的扩展名 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "text", "md", "markdown", "log", "json", "xml", "yaml", "yml", "csv", "tsv",
            "ini", "conf", "cfg", "properties", "toml", "env", "gitignore",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte",
            "css", "scss", "sass", "less",
            "java", "kt", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs",
            "php", "sh", "bat", "cmd", "ps1", "sql", "r", "lua", "pl", "swift", "dart", "gradle"
    );

    /** 图片扩展名（含 svg —— 用 <img> 展示不会执行脚本） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif", "svg"
    );

    /** 音视频扩展名 */
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp3", "wav", "ogg", "oga", "m4a", "flac", "aac",
            "mp4", "webm", "mov", "mkv", "m4v"
    );

    private final SessionFileManager sessionFileManager;

    public FileProxyController(SessionFileManager sessionFileManager) {
        this.sessionFileManager = sessionFileManager;
    }

    /**
     * @param path 会话文件引用（形如 "sessionId:fileName"）或历史数据里的文件系统路径。
     *             引用由 SessionFileManager 按当前 basePath 现算真实位置，因此项目目录搬迁后旧链接依然有效。
     */
    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxyLocalFile(@RequestParam("path") String path) {
        try {
            Path filePath = sessionFileManager.resolveRef(path).normalize();
            // 防路径遍历
            if (filePath.toString().contains("..")) {
                return ResponseEntity.badRequest().build();
            }
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // 允许常见媒体及文档类型
            String contentType = Files.probeContentType(filePath);
            if (contentType == null || !isAllowedContentType(contentType)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            byte[] data = Files.readAllBytes(filePath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setCacheControl("max-age=" + PROXY_CACHE_MAX_AGE);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("代理文件失败: {}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** 允许代理的 MIME 类型 */
    private boolean isAllowedContentType(String contentType) {
        if (contentType.startsWith("image/")) return true;
        if (contentType.startsWith("audio/")) return true;
        if (contentType.startsWith("video/")) return true;
        if (contentType.startsWith("text/")) return true;
        // 常见文档 / 压缩包
        return switch (contentType) {
            case "application/pdf",
                 "application/json",
                 "application/xml",
                 "application/zip",
                 "application/x-tar",
                 "application/gzip",
                 "application/x-7z-compressed",
                 "application/vnd.rar",
                 "application/msword",
                 "application/vnd.ms-excel",
                 "application/vnd.ms-powerpoint" -> true;
            default -> contentType.startsWith("application/vnd.openxmlformats-officedocument.");
        };
    }

    /* ==================== 本机文件：预览 / 系统打开 ==================== */

    /**
     * 读取本机文件用于前端内嵌预览。
     * <p>
     * 返回 {@code data} 形如：
     * <pre>{
     *   path, name, mime, size,
     *   kind: "html" | "text" | "image" | "media" | "pdf" | "binary",
     *   text: "..."   // 仅 html / text 带（超过上限会降级成 binary）
     * }</pre>
     * 前端按 kind 决定：html/text 走 srcdoc 预览，image/media/pdf 走 /file/proxy，
     * binary 或读取失败则调 {@link #openLocal} 交系统默认程序。
     *
     * @param path 本机绝对路径（前端从 file:// 链接解析后传入）
     */
    @GetMapping("/local/preview")
    public RestResponse previewLocal(@RequestParam("path") String path) {
        try {
            Path filePath = resolveLocalFile(path);
            if (filePath == null) {
                return new RestResponse().error("文件不存在或不是普通文件");
            }
            String name = filePath.getFileName().toString();
            String mime = detectMime(filePath);
            long size = Files.size(filePath);
            String kind = classifyExtension(extensionOf(name), mime);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", filePath.toString());
            data.put("name", name);
            data.put("mime", mime);
            data.put("size", size);

            if (("html".equals(kind) || "text".equals(kind)) && size <= MAX_PREVIEW_BYTES) {
                data.put("kind", kind);
                data.put("text", Files.readString(filePath, StandardCharsets.UTF_8));
            } else if ("html".equals(kind) || "text".equals(kind)) {
                // 文本但太大：不塞页面，交给系统打开
                data.put("kind", "binary");
            } else {
                data.put("kind", kind);
            }
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.warn("预览本机文件失败: {}", path, e);
            return new RestResponse().error("预览失败: " + e.getMessage());
        }
    }

    /**
     * 用系统默认程序打开本机文件（HTML 用浏览器、PDF 用阅读器、Office 用对应程序）。
     * 这是 file:// 被浏览器拦掉之后的兜底路径。
     */
    @GetMapping("/local/open")
    public RestResponse openLocal(@RequestParam("path") String path) {
        try {
            Path filePath = resolveLocalFile(path);
            if (filePath == null) {
                return new RestResponse().error("文件不存在或不是普通文件");
            }
            openWithDesktop(filePath.toFile());
            return new RestResponse().success(null);
        } catch (Exception e) {
            log.warn("打开本机文件失败: {}", path, e);
            return new RestResponse().error("打开失败: " + e.getMessage());
        }
    }

    /** 本机路径解析与校验：必须是已存在的普通文件 */
    private Path resolveLocalFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path filePath = Path.of(path.trim()).toAbsolutePath().normalize();
            return Files.isRegularFile(filePath) ? filePath : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 交系统默认程序打开；Desktop 不可用（无头环境等）时退回命令行 */
    private void openWithDesktop(File file) throws IOException {
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file);
                    return;
                }
            } catch (Exception e) {
                log.warn("Desktop.open 不可用，改用命令行打开: {}", e.getMessage());
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", file.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("xdg-open", file.getAbsolutePath());
        }
        pb.start();
    }

    /** 探测 MIME，拿不到时按扩展名兜底 */
    private String detectMime(Path file) {
        try {
            String mime = Files.probeContentType(file);
            if (mime != null && !mime.isBlank()) {
                return mime;
            }
        } catch (IOException ignored) {
            // 继续按扩展名兜底
        }
        return switch (extensionOf(file.getFileName().toString())) {
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs", "cjs" -> "text/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "avif" -> "image/avif";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "txt", "log" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }

    /** 归类预览方式：html / text / image / media / pdf / binary */
    private String classifyExtension(String ext, String mime) {
        if ("html".equals(ext) || "htm".equals(ext) || "text/html".equalsIgnoreCase(mime)) {
            return "html";
        }
        if (IMAGE_EXTENSIONS.contains(ext) || (mime != null && mime.startsWith("image/"))) {
            return "image";
        }
        if (MEDIA_EXTENSIONS.contains(ext)
                || (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/")))) {
            return "media";
        }
        if ("pdf".equals(ext) || "application/pdf".equalsIgnoreCase(mime)) {
            return "pdf";
        }
        if (TEXT_EXTENSIONS.contains(ext) || (mime != null && mime.startsWith("text/"))) {
            return "text";
        }
        return "binary";
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
