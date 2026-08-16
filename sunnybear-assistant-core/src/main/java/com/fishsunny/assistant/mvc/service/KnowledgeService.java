package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 知识库服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;

import java.util.List;

public interface KnowledgeService {

    /**
     * 获取所有知识条目。
     */
    List<KnowledgeRecord> getAllKnowledge();

    /**
     * 根据 ID 获取单个知识条目。
     */
    KnowledgeRecord getKnowledgeById(Integer id);

    /**
     * 添加或更新知识条目。
     * add 模式自动生成 ID 并对 title 做 embedding 编码；
     * update 模式更新已有条目，title 变化时重新编码。
     */
    KnowledgeRecord addOrUpdateKnowledge(Integer id, String title, String content, String mode);

    /**
     * 删除知识条目，返回被删除的记录。
     */
    KnowledgeRecord deleteKnowledge(Integer id);

    /**
     * 搜索知识条目列表。
     *
     * @param queryText 搜索字符串，当为 null 或空字符串时，表示返回全部记录并按时间倒序排列
     * @param offset 翻页偏移量
     * @return 分页匹配结果
     */
    ListKnowledgeResult listKnowledge(String queryText, int offset);

    /**
     * 构建系统提示词中的知识库片段。
     * 用 queryText 做 embedding 匹配知识库，
     * 合并 session 之前已注入的知识 ID，更新映射表，
     * 返回格式化的 [knowledge] 文本。
     *
     * @param sessionId 当前会话 ID
     * @param queryText 用于匹配的查询文本（用户最新消息）
     * @return 格式化的知识库文本，无匹配时返回空字符串
     */
    String buildKnowledgeSection(String sessionId, String queryText);

    /**
     * 查询某会话已注入的知识条目列表（来自 session_knowledge 映射表）
     *
     * @param sessionId 会话 ID
     * @return 已注入的知识条目列表，无记录时返回空列表
     */
    List<KnowledgeRecord> listSessionKnowledge(String sessionId);

    /**
     * 从会话的注入列表中移除一条知识条目（不删除知识条目本身）
     *
     * @param sessionId   会话 ID
     * @param knowledgeId 要移除的知识条目 ID
     * @return 是否移除成功（记录不存在或 id 不在列表中返回 false）
     */
    boolean removeSessionKnowledge(String sessionId, Integer knowledgeId);

    /**
     * 清空某会话的全部知识注入记录
     *
     * @param sessionId 会话 ID
     */
    void clearSessionKnowledge(String sessionId);

    public record ListKnowledgeResult(List<KnowledgeRecord> items, int total, int offset, int limit) {
    }
}