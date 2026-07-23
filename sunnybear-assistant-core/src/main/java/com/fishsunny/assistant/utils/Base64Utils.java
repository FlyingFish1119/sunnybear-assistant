package com.fishsunny.assistant.utils;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 23:54
 */

import org.springframework.util.StringUtils;

import java.util.Base64;

public class Base64Utils {


    /**
     * 从 data URI 中解析文件扩展名
     * <p>示例: "data:image/png;base64,..." → "png"
     *
     * @param dataUri data URI 字符串
     * @return 文件扩展名，解析失败返回 "bin"
     */
    public static String getExtensionFromDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return "bin";
        }
        int colonIdx = dataUri.indexOf(':');
        int semicolonIdx = dataUri.indexOf(';');
        if (colonIdx < 0 || semicolonIdx <= colonIdx) {
            return "bin";
        }
        String mimeType = dataUri.substring(colonIdx + 1, semicolonIdx);
        return getExtensionFromMimeType(mimeType);
    }

    /**
     * 根据 MIME 类型获取对应的文件扩展名
     *
     * @param mimeType MIME 类型字符串
     * @return 文件扩展名
     */
    public static String getExtensionFromMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType) || !mimeType.contains("/")) {
            return "bin";
        }
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "image/x-icon" -> "ico";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "text/css" -> "css";
            case "text/javascript" -> "js";
            case "application/json" -> "json";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "application/xml" -> "xml";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> {
                // 兜底：取 MIME 子类型作为扩展名，"svg+xml" → "svg"
                String subtype = mimeType.substring(mimeType.indexOf('/') + 1);
                if (subtype.contains("+")) {
                    subtype = subtype.substring(0, subtype.indexOf('+'));
                }
                yield subtype;
            }
        };
    }

    /**
     * 从 data URI 中解码 Base64 数据
     *
     * @param dataUri data URI 字符串
     * @return 解码后的字节数组，失败返回 null
     */
    public static byte[] decodeBase64FromDataUri(String dataUri) {
        if (dataUri == null) {
            return null;
        }
        int base64Idx = dataUri.indexOf(";base64,");
        if (base64Idx < 0) {
            return null;
        }
        String base64 = dataUri.substring(base64Idx + 8);
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 从 data URI 中提取裸 Base64 字符串
     * <p>示例: "data:audio/wav;base64,UklGRiQ..." → "UklGRiQ..."
     *
     * @param dataUri data URI 字符串
     * @return 裸 base64 字符串，失败返回 null
     */
    public static String extractBase64FromDataUri(String dataUri) {
        if (dataUri == null) {
            return null;
        }
        int base64Idx = dataUri.indexOf(";base64,");
        if (base64Idx < 0) {
            return null;
        }
        return dataUri.substring(base64Idx + 8);
    }

    /**
     * 根据文件扩展名获取 MIME 类型（{@link #getExtensionFromMimeType} 的反向操作）
     *
     * @param extension 文件扩展名（如 "png"、"wav"、"mp3"）
     * @return MIME 类型（如 "image/png"），未匹配默认返回 "application/octet-stream"
     */
    public static String getMimeTypeByExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "application/octet-stream";
        }
        // 利用已有映射的逆向：遍历已知 MIME，找到对应扩展名
        return switch (extension.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            case "tiff", "tif" -> "image/tiff";
            case "ico" -> "image/x-icon";
            case "txt" -> "text/plain";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "text/javascript";
            case "json" -> "application/json";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "xml" -> "application/xml";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            case "m4a" -> "audio/mp4";
            case "wma" -> "audio/x-ms-wma";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
        };
    }
}
