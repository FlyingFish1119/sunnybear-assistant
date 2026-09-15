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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ChatSession {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_CRON = "cron";

    private String id;

    private String name;

    private String type = "chat";

    public ChatSession setType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("Type must not be empty");
        }
        this.type = type;
        return this;
    }

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
     * 插件扩展字段（JSON 对象，语义由各插件自行约定，核心层不解析不解释）。
     * 例如角色/世界会话在此存放绑定资源 ID；核心层 token 统计在此存放 chat_ 前缀的累计值。
     * <p>DAO 层负责与数据库 TEXT 列的 JSON 字符串互相转换；为 null 表示未设置。
     * 写入时始终「合并」到已有 map，不得整体覆盖（避免抹掉其它插件写入的 key）。
     */
    private Map<String, Object> extension;

    public ChatSession setExtension(Map<String, Object> extension) {
        this.extension = extension;
        return this;
    }

    /** 取扩展字段（为 null 时惰性创建），便于 getExtension().put(...) 后回写 */
    public Map<String, Object> ensureExtension() {
        if (this.extension == null) {
            this.extension = new LinkedHashMap<>();
        }
        return this.extension;
    }

    public ChatSession() {
    }

    public ChatSession(String name) {
        this.name = name;
    }
}
