package com.fishsunny.assistant.dto;

/*
 * @Usage 文件数据 DTO —— 包含原始文件名和 base64 data URI
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/7
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.UUID;

@Data
@Accessors(chain = true)
public class FileData {

    /**
     * 会话文件链接标记：data 携带该前缀表示「文件已在会话目录里，勿重复落盘」，
     * 剥开后为相对会话文件目录的路径（允许子目录）。
     * 由文件资源栏「加载到发送栏」产生；ServiceProcessor 见到该标记直接拼引用，无视正文。
     */
    public static final String SESSION_FILE_LINK_PREFIX = "session-file-link:";

    /**
     * 原始文件名（如 "screenshot.png"）
     */
    private String name;

    public void setName(String name) {
        this.name = name == null ? UUID.randomUUID().toString() : name;
    }

    /**
     * base64 data URI（格式: data:{mime};base64,{data}）
     */
    private String data;

    public void setData(String data) {
        this.data = data == null ? "" : data;
    }

    public FileData() {
    }

    public FileData(String name, String data) {
        setName(name);
        setData(data);
    }
}
