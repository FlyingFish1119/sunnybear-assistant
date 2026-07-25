package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 知识库服务实现 —— 管理知识条目 CRUD、embedding 编码、语义匹配与 session 注入
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.EmbeddingHttpHandler;
import com.fishsunny.assistant.engine.protocol.EmbeddingAPI;
import com.fishsunny.assistant.engine.protocol.embedding.StandardEmbeddingRequest;
import com.fishsunny.assistant.engine.protocol.embedding.StandardEmbeddingResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.protocol.project.entity.SessionKnowledgeRecord;
import com.fishsunny.assistant.mvc.dao.KnowledgeRepository;
import com.fishsunny.assistant.mvc.dao.SessionKnowledgeRepository;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.settings.KnowledgeSettings;
import com.fishsunny.assistant.utils.CosineSimilarityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeServiceImplement implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImplement.class);

    public static final String MODE_ADD = "add";
    public static final String MODE_UPDATE = "update";

    private final KnowledgeRepository knowledgeRepository;
    private final SessionKnowledgeRepository sessionKnowledgeRepository;
    private final EmbeddingHttpHandler embeddingHttpHandler;
    private final EmbeddingAPI embeddingAPI;
    private final KnowledgeSettings knowledgeSettings;
    private final ObjectMapper objectMapper;

    public KnowledgeServiceImplement(KnowledgeRepository knowledgeRepository,
                                     SessionKnowledgeRepository sessionKnowledgeRepository,
                                     EmbeddingHttpHandler embeddingHttpHandler,
                                     EmbeddingAPI embeddingAPI,
                                     KnowledgeSettings knowledgeSettings,
                                     ObjectMapper objectMapper) {
        this.knowledgeRepository = knowledgeRepository;
        this.sessionKnowledgeRepository = sessionKnowledgeRepository;
        this.embeddingHttpHandler = embeddingHttpHandler;
        this.embeddingAPI = embeddingAPI;
        this.knowledgeSettings = knowledgeSettings;
        this.objectMapper = objectMapper;
    }



    // ========================= 知识 CRUD =========================

    @Override
    public List<KnowledgeRecord> getAllKnowledge() {
        return knowledgeRepository.selectAll();
    }

    @Override
    public KnowledgeRecord getKnowledgeById(Integer id) {
        if (id == null) {
            return null;
        }
        return knowledgeRepository.selectById(id);
    }

    @Override
    public KnowledgeRecord addOrUpdateKnowledge(Integer id, String title, String content, String mode) {
        if (MODE_ADD.equalsIgnoreCase(mode)) {
            List<Float> embedding = encodeTitle(title);
            KnowledgeRecord record = new KnowledgeRecord()
                    .setTitle(title)
                    .setContent(content)
                    .setEmbedding(embedding);
            KnowledgeRecord saved = knowledgeRepository.insert(record);
            log.info("新增知识条目: id={}, title={}", saved.getId(), saved.getTitle());
            return saved;
        } else if (MODE_UPDATE.equalsIgnoreCase(mode)) {
            if (id == null) {
                throw new IllegalArgumentException("update 模式下 id 不能为空");
            }
            KnowledgeRecord existing = knowledgeRepository.selectById(id);
            if (existing == null) {
                throw new IllegalArgumentException("知识条目不存在: id=" + id);
            }
            // title 变化时重新编码，否则沿用旧向量
            boolean titleChanged = !title.equals(existing.getTitle());
            existing.setTitle(title);
            existing.setContent(content);
            if (titleChanged) {
                existing.setEmbedding(encodeTitle(title));
            }
            KnowledgeRecord saved = knowledgeRepository.update(existing);
            log.info("更新知识条目: id={}, title={}", saved.getId(), saved.getTitle());
            return saved;
        } else {
            throw new IllegalArgumentException("不支持的模式: " + mode + "，仅支持 add 和 update");
        }
    }

    @Override
    public KnowledgeRecord deleteKnowledge(Integer id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        KnowledgeRecord deleted = knowledgeRepository.deleteById(id);
        if (deleted != null) {
            log.info("删除知识条目: id={}, title={}", deleted.getId(), deleted.getTitle());
        } else {
            log.warn("删除知识条目失败，记录不存在: id={}", id);
        }
        return deleted;
    }

    @Override
    public ListKnowledgeResult listKnowledge(String queryText, int offset) {
        int limit = 10;
        if (offset < 0) {
            offset = 0;
        }

        // 无搜索词：按 create_time 降序分页返回全部
        if (!StringUtils.hasText(queryText)) {
            List<KnowledgeRecord> all = knowledgeRepository.selectAll();
            all.sort(Comparator.comparing(KnowledgeRecord::getCreateTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            int total = all.size();
            int fromIndex = Math.min(offset, total);
            int toIndex = Math.min(fromIndex + limit, total);
            List<KnowledgeRecord> page = new ArrayList<>(all.subList(fromIndex, toIndex));

            return new ListKnowledgeResult(page, total, offset, limit);
        }

        // 有搜索词：embedding + 余弦相似度匹配
        List<Float> queryEmbedding = encodeTitle(queryText);
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("查询 embedding 失败，返回空列表");
            return new ListKnowledgeResult(new ArrayList<>(), 0, offset, limit);
        }

        List<KnowledgeRecord> allEntries = knowledgeRepository.selectAll();

        // 逐条计算相似度，跳过 embedding 为 null/empty 的条目
        List<AbstractMap.SimpleEntry<KnowledgeRecord, Float>> scored = new ArrayList<>();
        for (KnowledgeRecord entry : allEntries) {
            if (entry.getEmbedding() == null || entry.getEmbedding().isEmpty()) {
                continue;
            }
            try {
                float similarity = CosineSimilarityUtil.cosine(queryEmbedding, entry.getEmbedding());
                scored.add(new AbstractMap.SimpleEntry<>(entry, similarity));
            } catch (Exception e) {
                log.warn("计算相似度失败: title={}, error={}", entry.getTitle(), e.getMessage());
            }
        }

        // 按相似度降序排列
        scored.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        int total = scored.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(fromIndex + limit, total);
        List<KnowledgeRecord> page = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            page.add(scored.get(i).getKey());
        }

        return new ListKnowledgeResult(page, total, offset, limit);
    }

    // ========================= 匹配与注入 =========================

    @Override
    public String buildKnowledgeSection(String sessionId, String queryText) {
        if (!Boolean.TRUE.equals(knowledgeSettings.getEnable())) {
            return "";
        }
        if (!StringUtils.hasText(queryText)) {
            return "";
        }

        float threshold = knowledgeSettings.getSimilarityThreshold() != null
                ? knowledgeSettings.getSimilarityThreshold() : 0.7f;

        // 1. 对用户查询文本做 embedding
        List<Float> queryEmbedding = encodeTitle(queryText);
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("查询 embedding 失败，跳过知识库匹配");
            return buildFromExistingOnly(sessionId);
        }

        // 2. 加载所有知识条目，计算余弦相似度
        List<KnowledgeRecord> allEntries = knowledgeRepository.selectAll();
        if (allEntries.isEmpty()) {
            return "";
        }

        // 3. 加载 session 已注入的知识 ID 集合
        Set<Integer> injectedIds = getInjectedKnowledgeIds(sessionId);

        // 4. 匹配新条目（过滤掉已注入的，避免重复判断）
        for (KnowledgeRecord entry : allEntries) {
            if (injectedIds.contains(entry.getId())) {
                continue; // 已注入的不需要重新匹配
            }
            if (entry.getEmbedding() == null || entry.getEmbedding().isEmpty()) {
                continue;
            }
            try {
                float similarity = CosineSimilarityUtil.cosine(queryEmbedding, entry.getEmbedding());
                if (similarity >= threshold) {
                    injectedIds.add(entry.getId());
                    log.debug("知识库匹配: title={}, similarity={}", entry.getTitle(), similarity);
                }
            } catch (Exception e) {
                log.warn("计算相似度失败: title={}, error={}", entry.getTitle(), e.getMessage());
            }
        }

        // 5. 更新 session 映射表（合并新旧 ID）
        try {
            SessionKnowledgeRecord mapping = new SessionKnowledgeRecord()
                    .setSessionId(sessionId)
                    .setKnowledgeIds(objectMapper.writeValueAsString(new ArrayList<>(injectedIds)));
            sessionKnowledgeRepository.upsertBySessionId(mapping);
        } catch (Exception e) {
            log.error("更新 session 知识映射失败: sessionId={}, error={}", sessionId, e.getMessage());
        }

        // 6. 收集全部应注入的条目
        List<KnowledgeRecord> allInjected = allEntries.stream()
                .filter(e -> injectedIds.contains(e.getId()))
                .collect(Collectors.toList());

        if (allInjected.isEmpty()) {
            return "";
        }

        // 7. 格式化输出
        return formatKnowledgeSection(allInjected);
    }

    // ========================= 私有辅助方法 =========================

    /**
     * 对文本做 embedding 编码，返回向量。
     */
    private List<Float> encodeTitle(String text) {
        try {
            StandardEmbeddingRequest request = new StandardEmbeddingRequest()
                    .setModel(embeddingAPI.getModel())
                    .setInput(text);

            StandardEmbeddingResponse response = (StandardEmbeddingResponse)
                    embeddingHttpHandler.embed(request, StandardEmbeddingResponse.class, embeddingAPI, null);

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                log.warn("Embedding API 返回空: text={}", text);
                return null;
            }

            return response.getData().get(0).getEmbedding();
        } catch (Exception e) {
            log.error("Embedding 编码失败: text={}, error={}", text, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取 session 已注入的知识 ID 集合。
     */
    private Set<Integer> getInjectedKnowledgeIds(String sessionId) {
        try {
            SessionKnowledgeRecord mapping = sessionKnowledgeRepository.selectBySessionId(sessionId);
            if (mapping == null || !StringUtils.hasText(mapping.getKnowledgeIds())) {
                return new HashSet<>();
            }
            List<Integer> ids = objectMapper.readValue(mapping.getKnowledgeIds(), new TypeReference<List<Integer>>() {});
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("解析 session 知识映射失败: sessionId={}, error={}", sessionId, e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * 当查询 embedding 失败时，仅基于已有注入构建知识片段（不匹配新条目）。
     */
    private String buildFromExistingOnly(String sessionId) {
        Set<Integer> injectedIds = getInjectedKnowledgeIds(sessionId);
        if (injectedIds.isEmpty()) {
            return "";
        }
        List<KnowledgeRecord> entries = knowledgeRepository.selectAll();
        List<KnowledgeRecord> injected = entries.stream()
                .filter(e -> injectedIds.contains(e.getId()))
                .collect(Collectors.toList());
        return injected.isEmpty() ? "" : formatKnowledgeSection(injected);
    }

    /**
     * 格式化知识条目列表为系统提示词片段。
     */
    private String formatKnowledgeSection(List<KnowledgeRecord> entries) {
        StringBuilder sb = new StringBuilder("\n[knowledge]\n");
        sb.append("以下是从知识库中检索到的相关知识，可能对当前对话有帮助：\n");
        for (int i = 0; i < entries.size(); i++) {
            KnowledgeRecord entry = entries.get(i);
            sb.append(i + 1).append(". 【").append(entry.getTitle()).append("】")
                    .append(entry.getContent()).append("\n");
        }
        return sb.toString();
    }
}