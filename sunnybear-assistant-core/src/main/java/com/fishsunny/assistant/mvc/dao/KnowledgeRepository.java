package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 知识库数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;

import java.util.List;

public interface KnowledgeRepository {

    KnowledgeRecord insert(KnowledgeRecord record);

    KnowledgeRecord update(KnowledgeRecord record);

    KnowledgeRecord deleteById(Integer id);

    KnowledgeRecord selectById(Integer id);

    List<KnowledgeRecord> selectAll();
}
