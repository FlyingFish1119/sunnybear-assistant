package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 项目（Project）—— 围绕同一件事的一批会话的归集容器。
 *        项目本身是独立元数据，不寄居在会话表里：会话通过 extension.chat_project_id 反向指向它。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ChatProject {

    private String id;

    private String name;

    /** 项目描述：给 Jev 判定「这句话属不属于这个项目」当语义线索；可为空 */
    private String description;

    /** 会话列表竖色条的颜色（#RRGGBB）；空串表示由前端按 id 兜底取色 */
    private String color;

    /**
     * 自动追加的文件名清单：新建会话的第一条消息，会把这里的每个文件挂进上下文。
     * <p>只存项目根下的纯文件名（项目资料不做子目录）；表里是 JSON 数组文本，空清单存 {@code []}。
     */
    private List<String> autoAppendFiles = new ArrayList<>();

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 项目下的会话数。
     * <p>非持久化字段（chat_project 表没有这一列）：列表接口按需填充，供前端在项目行上显示「N 个会话」；
     * 单条查询、新建、修改返回的对象里为 null。
     */
    private Integer sessionCount;

    public ChatProject() {
    }

    public ChatProject(String name) {
        this.name = name;
    }
}
