package com.fishsunny.assistant.mvc.service;

import com.fishsunny.assistant.dto.ChatExportFileData;

/**
 * 会话导出业务层接口
 */
public interface ChatExportService {

    /**
     * 导出会话对话内容
     *
     * @param sessionId 会话 ID
     * @param format    导出格式：markdown(md) / text(txt) / json，null 或未知格式按 markdown 处理
     * @return 导出文件数据
     */
    ChatExportFileData export(String sessionId, String format);
}
