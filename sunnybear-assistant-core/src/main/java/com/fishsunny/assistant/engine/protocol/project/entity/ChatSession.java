package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 06:09
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class ChatSession {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_CRON = "cron";

    private String id;

    private String name;

    private String type = "chat";

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private Boolean enablePro = false;

    /**
     * 无审查模式：开启后该会话内所有工具的用户确认与 AI 危险审查全部失效（完全无审查）。
     * 注意：与工具内已有的 AUTO 模式常量（FileWriteTool.AUTO 等，含义为"危险操作需确认"）语义相反，勿混淆。
     */
    private Boolean unreviewed = false;

    /**
     * 插件扩展字段（JSON 字符串，语义由各插件自行约定，核心层不解析不解释）。
     * 例如角色/世界会话在此存放绑定资源 ID；普通会话与定时任务会话为 null。
     */
    private String extension;

    public ChatSession() {
    }

    public ChatSession(String name) {
        this.name = name;
    }

    public Path buildSessionFilePath(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = System.getProperty("user.dir");
        }
        return Path.of(baseUrl, "session", id, "file");
    }
}
