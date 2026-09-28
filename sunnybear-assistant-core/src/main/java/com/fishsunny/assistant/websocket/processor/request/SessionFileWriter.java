package com.fishsunny.assistant.websocket.processor.request;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:41
 */

import com.fishsunny.assistant.dto.FileData;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.CoreFileManager;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class SessionFileWriter {

    private final SessionFileManager sessionFileManager;
    private final CoreFileManager coreFileManager;

    public SessionFileWriter(SessionFileManager sessionFileManager, CoreFileManager coreFileManager) {
        this.sessionFileManager = sessionFileManager;
        this.coreFileManager = coreFileManager;
    }

    /** 会话附件存储文件名的毫秒级时间戳格式 */
    private static final DateTimeFormatter SESSION_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");


    /**
     * 将 base64 编码的文件数据写入会话目录，保留原始文件名
     * <p>命名规则: {原始文件名主干}_{时间戳}.{ext}；毫秒级时间戳保证多次上传同名文件互不覆盖，
     * 无需再探测磁盘判断编号是否已被占用。同一次上传内出现完全同名的文件时，追加序号区分。
     *
     * @param files       文件数据列表
     * @param chatSession 会话对象
     * @return 写入成功的文件引用列表（形如 "sessionId:fileName"），用于前端 /file/proxy 代理获取
     */
    public List<String> writeSessionFile(List<FileData> files, ChatSession chatSession) {
        List<String> writtenPaths = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return writtenPaths;
        }
        String sessionId = chatSession.getId();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            FileData fileData = files.get(i);
            if (fileData == null || !StringUtils.hasText(fileData.getData())) {
                log.warn("文件数据为空，跳过索引: {}", i);
                continue;
            }
            String dataUri = fileData.getData();
            // 会话文件链接（session-file-link:{相对路径}）：文件已在会话目录里，
            // 直接拼引用不重复落盘。引用归属以后端当前会话为准，客户端的正文无视。
            if (dataUri.startsWith(FileData.SESSION_FILE_LINK_PREFIX)) {
                String relPath = dataUri.substring(FileData.SESSION_FILE_LINK_PREFIX.length()).trim();
                try {
                    Path filePath = sessionFileManager.resolveSessionFilePath(sessionId, relPath);
                    if (Files.isRegularFile(filePath)) {
                        writtenPaths.add(sessionFileManager.buildRef(sessionId, relPath));
                    } else {
                        log.warn("会话文件链接指向的文件不存在，跳过: {}", relPath);
                    }
                } catch (Exception e) {
                    log.warn("会话文件链接解析失败，跳过: {} ({})", relPath, e.getMessage());
                }
                continue;
            }
            // 核心文件链接（core-file-link:{相对路径}）：前端只交引用、不搬内容，
            // 后端同机从核心库复制一份进本会话文件目录，再按普通会话文件落库。
            // 源文件缺失/复制失败只跳过该附件并记日志，不让整条消息发不出去。
            if (dataUri.startsWith(FileData.CORE_FILE_LINK_PREFIX)) {
                String corePath = dataUri.substring(FileData.CORE_FILE_LINK_PREFIX.length()).trim();
                try {
                    writtenPaths.add(coreFileManager.copyCoreFileToSession(sessionId, corePath));
                } catch (Exception e) {
                    log.warn("核心文件链接复制进会话失败，跳过: {} ({})", corePath, e.getMessage());
                }
                continue;
            }
            byte[] data = Base64Utils.decodeBase64FromDataUri(dataUri);
            if (data == null) {
                log.warn("无法解析文件数据，跳过索引: {}", i);
                continue;
            }

            String fileName = buildSessionFileName(fileData, dataUri, usedNames);
            try {
                writtenPaths.add(sessionFileManager.writeSessionFile(sessionId, fileName, data));
            } catch (IOException e) {
                log.error("写入文件失败 [{}]: {}", i, e.getMessage());
            }
        }
        return writtenPaths;
    }

    /**
     * 构造会话目录内的存储文件名：{原始文件名主干}_{毫秒时间戳}.{ext}
     *
     * @param fileData  文件数据（含原始文件名）
     * @param dataUri   base64 data URI，用于原文件名缺失/无扩展名时兜底推断扩展名
     * @param usedNames 本次上传内已用过的文件名，撞名时追加序号
     * @return 仅含文件名（不含目录）的存储名
     */
    private String buildSessionFileName(FileData fileData, String dataUri, Set<String> usedNames) {
        String rawName = fileData.getName();
        String stem;
        String ext;
        if (StringUtils.hasText(rawName)) {
            int dot = rawName.lastIndexOf('.');
            if (dot > 0 && dot < rawName.length() - 1) {
                stem = rawName.substring(0, dot);
                ext = rawName.substring(dot + 1);
            } else {
                stem = rawName;
                ext = "";
            }
        } else {
            stem = "file";
            ext = "";
        }
        // 清洗路径与非法字符，防止夹带路径穿越或生成非法文件名
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        if (!StringUtils.hasText(stem)) {
            stem = "file";
        }
        if (!StringUtils.hasText(ext)) {
            ext = Base64Utils.getExtensionFromDataUri(dataUri);
        }

        String ts = LocalDateTime.now().format(SESSION_FILE_TIME);
        String candidate = stem + "_" + ts + "." + ext;
        int seq = 2;
        while (!usedNames.add(candidate)) {
            candidate = stem + "_" + ts + "_" + seq + "." + ext;
            seq++;
        }
        return candidate;
    }
}
