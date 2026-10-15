package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 项目服务 —— 项目的增删改查，以及「会话归入 / 移出项目」。
 *        项目会话的绑定关系存在 chat_session.extension 的 chat_project_id 键上，
 *        读写统一走 ChatSessionService.mergeExtension（append-only 合并，不碰别人的键）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;

import java.util.List;

public interface ChatProjectService {

    /** 全部项目，按最近改动倒序 */
    public List<ChatProject> listAll();

    public ChatProject findById(String id);

    /**
     * 新建项目。name 必填且不可与已有项目重名；
     * color 为空时从预设色板自动分配一个（重名会抛 IllegalArgumentException）。
     */
    public ChatProject create(String name, String description, String color);

    /**
     * 修改项目。name 传空视为非法；description / color 传 null 表示该项不变、传空串表示清空。
     */
    public ChatProject update(String id, String name, String description, String color);

    /** 删除项目，并把所有挂在它下面的会话解绑（会话与消息都保留） */
    public void delete(String id);

    /** 把会话归入项目；会话或项目不存在会抛 IllegalArgumentException */
    public ChatSession attachSession(String sessionId, String projectId);

    /** 把会话移出项目（幂等：本来就没归属时也当成功） */
    public ChatSession detachSession(String sessionId);

    /** 列出某个项目下的全部会话（按最近更新倒序） */
    public List<ChatSession> listSessions(String projectId);

    /**
     * 项目「自动追加」清单里各文件的绝对路径 —— 新建会话第一条消息要挂的就是这批。
     * <p>按清单顺序解析，文件已被删掉的条目静默跳过；项目不存在或清单为空时返回空列表。
     */
    public List<String> listAutoAppendPaths(String projectId);

    /**
     * 往自动追加清单里加一项 / 摘一项（幂等：状态已是要的样子就什么也不用写）。
     * <p>不动 update_time：切个开关不该让项目在列表里跳位置。
     *
     * @param auto true = 加入；false = 摘掉
     * @return 变更后的项目
     */
    public ChatProject updateAutoAppendFile(String projectId, String fileName, boolean auto);
}
