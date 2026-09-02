package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 多模态工具结果处理接口。工具执行完毕后，若 ToolHandler 实现了本接口，
 *        ToolExecutor 会把工具的多模态内容（base64）交给工具自行落盘，
 *        并把 data 由 base64 替换为文件路径，供外层读取 contents 中的路径后复用 fillFiles 转换。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;


public interface MultimodalResultAble {

    default void writeFile(List<MultimodalContent> contents) throws IOException {
        if (CollectionUtils.isEmpty(contents)) {
            return;
        }
        for (MultimodalContent content : contents) {
            byte[] bytes = decodeBase64(content.getData());
            if (bytes == null) {
                continue;
            }
            Path path = Path.of(content.getPath());
            File file = path.toFile();
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                throw new IOException("Failed to create directory: " + file.getParentFile().getAbsolutePath());
            }
            Files.write(path, bytes);
        }
    }

    private static byte[] decodeBase64(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        // 兼容 data URI：data:image/png;base64,xxxx → 剥前缀
        if (data.startsWith("data:")) {
            int comma = data.indexOf(',');
            if (comma > 0) {
                data = data.substring(comma + 1);
            }
        }
        // 含 URL-safe 字符走 getUrlDecoder，否则 MIME（容换行）
        if (data.indexOf('-') >= 0 || data.indexOf('_') >= 0) {
            return Base64.getUrlDecoder().decode(data);
        }
        return Base64.getMimeDecoder().decode(data);
    }
}
