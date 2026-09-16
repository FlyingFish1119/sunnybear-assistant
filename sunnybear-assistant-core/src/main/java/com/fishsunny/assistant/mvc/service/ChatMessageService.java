package com.fishsunny.assistant.mvc.service;

/*
 * @Usage ChatMessage 业务层接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;

import java.util.List;

public interface ChatMessageService {

    /**
     * 保存一条聊天消息
     *
     * @param chatMessage 消息对象
     * @return 受影响的行数
     */
    public ChatMessage save(ChatMessage chatMessage) throws Exception;

    /**
     * 更新一条聊天消息
     *
     * @param chatMessage 消息对象（需包含 id）
     * @return 受影响的行数
     */
    public ChatMessage update(ChatMessage chatMessage) throws Exception;

    /**
     * 替换一条聊天消息
     *
     * @param chatMessage 消息对象（需包含 id）
     * @return 受影响的行数
     */
    public ChatMessage replace(ChatMessage chatMessage) throws Exception;

    /**
     * 根据 ID 删除一条消息
     *
     * @param id 消息 ID
     * @return 受影响的行数
     */
    public ChatMessage deleteById(String id) throws Exception;

    /**
     * 删除某个会话下的所有消息
     *
     * @param sessionId 会话 ID
     * @return 受影响的行数
     */
    public int deleteBySessionId(String sessionId) throws Exception;

    /**
     * 根据 ID 查询一条消息
     *
     * @param id 消息 ID
     * @return 消息对象，未找到返回 null
     */
    public ChatMessage findById(String id) throws Exception;

    /**
     * 查询某个会话下的所有消息，包括未启用的消息
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<ChatMessage> findBySessionId(String sessionId) throws Exception;

    /**
     * 查询某个会话下的对话历史
     *
     * @param sessionId 会话 ID
     * @return 对话链消息列表
     */
    public List<ChatMessage> getConversationHistory(String sessionId) throws Exception;

    /**
     * 在同父节点的兄弟分支之间切换（左右箭头导航）
     *
     * @param messageId 当前消息 ID
     * @param direction 方向："left" 或 "right"
     * @return 受影响的行数
     */
    public int switchBranch(String messageId, String direction) throws Exception;

    /**
     * 停用某个消息及其所有子孙消息（replace 模式用）
     *
     * @param messageId 消息 ID
     * @return 受影响的行数
     */
    public int deactivateBranch(String messageId) throws Exception;

    /**
     * 删除用户消息及其所有子孙消息（物理删除），并异步触发向量重生成
     *
     * @param id 用户消息 ID
     */
    public void deleteUserMessageWithChildren(String id);

    /**
     * 编辑助手消息内容，并异步触发向量重生成
     *
     * @param id      助手消息 ID
     * @param content 新内容
     */
    public void editAssistantMessage(String id, String content);

    /**
     * 上下文压缩落库：在同一事务内先物理清空该会话全部消息，再按给定顺序重新插入成一条链
     * （首条作为 root，parentId=null；后续依次挂到前一条上）。
     * <p>
     * 整体原子：任一步失败全部回滚，避免「删了却插不回去」导致会话消息丢失。
     * 入参消息需已 makeInsertable（canInsert=true），本方法会覆盖其 sessionId/parentId。
     *
     * @param sessionId 会话 ID
     * @param messages  重建后的消息链（顺序即父子顺序）
     * @return 重新插入、带新 id 的消息链
     */
    public List<ChatMessage> replaceSessionMessages(String sessionId, List<ChatMessage> messages) throws Exception;
}
