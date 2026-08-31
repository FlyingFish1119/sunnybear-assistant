package com.fishsunny.assistant.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 会话导出文件数据
 */
@Data
@Accessors(chain = true)
public class ChatExportFileData {

    /** 导出文件内容（md/txt 文本类已带 UTF-8 BOM） */
    private String content;

    /** 文件扩展名：md / txt / json */
    private String ext;

    /** MIME 类型：text/markdown / text/plain / application/json */
    private String mime;
}
