package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 多模态工具结果处理接口。工具执行完毕后，若 ToolHandler 实现了本接口，
 *        ToolExecutor 会把工具的多模态内容（base64）交给本接口落盘。
 *        工具只需在 MultimodalContent 里给出会话内的文件名，落盘后这里会把
 *        path 回写为可移植引用（形如 "sessionId:fileName"），供外层读取 contents 后
 *        经 SessionFileManager.loadSessionFile 展开为 data URI。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;


public interface MultimodalResultAble {

    /**
     * 把多模态内容落盘到会话文件目录。
     *
     * @param contents           多模态内容，其 path 为会话内文件名，落盘后被回写为引用
     * @param sessionFileManager 会话文件管理器
     * @param sessionId          当前会话 ID
     */
    default void writeFile(List<MultimodalContent> contents,
                           SessionFileManager sessionFileManager,
                           String sessionId) throws IOException {
        if (CollectionUtils.isEmpty(contents)) {
            return;
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new IOException("无法确定当前会话，多模态结果无法落盘");
        }
        for (MultimodalContent content : contents) {
            byte[] bytes = decodeBase64(content.getData());
            if (bytes == null) {
                continue;
            }
            content.setPath(sessionFileManager.writeSessionFile(sessionId, content.getPath(), bytes));
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
