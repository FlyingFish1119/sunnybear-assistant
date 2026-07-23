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
}
