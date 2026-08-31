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
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.EmbeddingHttpHandler;
import com.fishsunny.assistant.engine.protocol.EmbeddingAPI;
import com.fishsunny.assistant.engine.protocol.embedding.StandardEmbeddingRequest;
import com.fishsunny.assistant.engine.protocol.embedding.StandardEmbeddingResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.protocol.project.entity.SessionKnowledgeRecord;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.dao.KnowledgeRepository;
import com.fishsunny.assistant.mvc.dao.SessionKnowledgeRepository;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.KnowledgeSettings;
import com.fishsunny.assistant.utils.CosineSimilarityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AISettings cubAISettings;
    private final ChatHttpHandler chatHttpHandler;

    public KnowledgeServiceImplement(KnowledgeRepository knowledgeRepository,
                                     SessionKnowledgeRepository sessionKnowledgeRepository,
                                     EmbeddingHttpHandler embeddingHttpHandler,
                                     EmbeddingAPI embeddingAPI,
                                     KnowledgeSettings knowledgeSettings,
                                     ObjectMapper objectMapper,
                                     @Qualifier(AISettings.CUB) AISettings cubAISettings,
                                     ChatHttpHandler chatHttpHandler) {
        this.knowledgeRepository = knowledgeRepository;
        this.sessionKnowledgeRepository = sessionKnowledgeRepository;
        this.embeddingHttpHandler = embeddingHttpHandler;
        this.embeddingAPI = embeddingAPI;
        this.knowledgeSettings = knowledgeSettings;
        this.objectMapper = objectMapper;
        this.cubAISettings = cubAISettings;
        this.chatHttpHandler = chatHttpHandler;
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
    public KnowledgeRecord addOrUpdateKnowledge(Integer id, String intro, String content, String mode) {
        if (MODE_ADD.equalsIgnoreCase(mode)) {
            List<Float> embedding = encodeIntro(intro);
            KnowledgeRecord record = new KnowledgeRecord()
                    .setIntro(intro)
                    .setContent(content)
                    .setEmbedding(embedding);
            KnowledgeRecord saved = knowledgeRepository.insert(record);
            log.info("新增知识条目: id={}, intro={}", saved.getId(), saved.getIntro());
            return saved;
        } else if (MODE_UPDATE.equalsIgnoreCase(mode)) {
            if (id == null) {
                throw new IllegalArgumentException("update 模式下 id 不能为空");
            }
            KnowledgeRecord existing = knowledgeRepository.selectById(id);
            if (existing == null) {
                throw new IllegalArgumentException("知识条目不存在: id=" + id);
            }
            // intro 变化时重新编码，否则沿用旧向量
            boolean introChanged = !intro.equals(existing.getIntro());
            existing.setIntro(intro);
            existing.setContent(content);
            if (introChanged) {
                existing.setEmbedding(encodeIntro(intro));
            }
            KnowledgeRecord saved = knowledgeRepository.update(existing);
            log.info("更新知识条目: id={}, intro={}", saved.getId(), saved.getIntro());
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
            log.info("删除知识条目: id={}, intro={}", deleted.getId(), deleted.getIntro());
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
        List<Float> queryEmbedding = encodeIntro(queryText);
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
                log.warn("计算相似度失败: intro={}, error={}", entry.getIntro(), e.getMessage());
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

    // ========================= 会话知识库管理 =========================

    @Override
    public List<KnowledgeRecord> listSessionKnowledge(String sessionId) {
        Set<Integer> injectedIds = getInjectedKnowledgeIds(sessionId);
        if (injectedIds.isEmpty()) {
            return new ArrayList<>();
        }
        return knowledgeRepository.selectAll().stream()
                .filter(e -> injectedIds.contains(e.getId()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean removeSessionKnowledge(String sessionId, Integer knowledgeId) {
        if (!StringUtils.hasText(sessionId) || knowledgeId == null) {
            return false;
        }
        try {
            SessionKnowledgeRecord mapping = sessionKnowledgeRepository.selectBySessionId(sessionId);
            if (mapping == null || !StringUtils.hasText(mapping.getKnowledgeIds())) {
                return false;
            }
            List<Integer> ids = objectMapper.readValue(mapping.getKnowledgeIds(), new TypeReference<List<Integer>>() {});
            if (!ids.remove(knowledgeId)) {
                return false;
            }
            if (ids.isEmpty()) {
                sessionKnowledgeRepository.deleteBySessionId(sessionId);
            } else {
                SessionKnowledgeRecord updated = new SessionKnowledgeRecord()
                        .setSessionId(sessionId)
                        .setKnowledgeIds(objectMapper.writeValueAsString(ids));
                sessionKnowledgeRepository.upsertBySessionId(updated);
            }
            log.info("从会话 {} 移除知识条目: id={}", sessionId, knowledgeId);
            return true;
        } catch (Exception e) {
            log.error("移除会话知识失败: sessionId={}, knowledgeId={}, error={}", sessionId, knowledgeId, e.getMessage());
            return false;
        }
    }

    @Override
    public void clearSessionKnowledge(String sessionId) {
        sessionKnowledgeRepository.deleteBySessionId(sessionId);
        log.info("已清空会话 {} 的知识注入记录", sessionId);
    }

    // ========================= 匹配与注入 =========================

    @Override
    public KnowledgeSection buildKnowledgeSection(String sessionId, String queryText) {
        if (!Boolean.TRUE.equals(knowledgeSettings.getEnable())) {
            return new KnowledgeSection("", false);
        }
        if (!StringUtils.hasText(queryText)) {
            return new KnowledgeSection("", false);
        }

        // 1. 加载所有知识条目
        List<KnowledgeRecord> allEntries = knowledgeRepository.selectAll();
        if (allEntries.isEmpty()) {
            return new KnowledgeSection("", false);
        }

        // 2. 加载 session 已注入的知识 ID 集合
        Set<Integer> injectedIds = getInjectedKnowledgeIds(sessionId);

        // 3. 让 cub 从全部条目中选出与当前问题相关的条目
        List<Integer> selectedIds = selectKnowledgeByCub(allEntries, queryText);
        if (selectedIds == null) {
            // cub 调用/解析失败，降级为只注入历史已注入条目
            return new KnowledgeSection(buildFromExistingOnly(sessionId), false);
        }

        // 4. 与已注入的去重合并（只接受真实存在的条目 id）
        Set<Integer> validIds = allEntries.stream()
                .map(KnowledgeRecord::getId)
                .collect(Collectors.toSet());
        boolean hasNew = false;
        for (Integer id : selectedIds) {
            if (id != null && validIds.contains(id) && injectedIds.add(id)) {
                hasNew = true;
                log.debug("知识库 cub 选择新条目: id={}", id);
            }
        }
        // 本轮没有新条目，只注入历史已注入条目（不视为本轮命中）
        if (!hasNew) {
            return new KnowledgeSection(buildFromExistingOnly(sessionId), false);
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
            return new KnowledgeSection("", false);
        }

        // 7. 格式化输出
        return new KnowledgeSection(formatKnowledgeSection(allInjected), true);
    }

    /**
     * 调用 cub 模型，从全部知识条目中选出与用户问题相关的条目 id 列表。
     *
     * @return 选中的条目 id 列表；cub 调用或解析失败时返回 null（由调用方降级为历史注入）
     */
    private List<Integer> selectKnowledgeByCub(List<KnowledgeRecord> allEntries, String queryText) {
        try {
            StringBuilder entriesText = new StringBuilder();
            for (KnowledgeRecord entry : allEntries) {
                entriesText.append(entry.getId()).append(". ").append(entry.getIntro()).append("\n");
            }
            String prompt = """
            你是一个知识库条目选择器。用户正在进行一段对话，你需要从知识库条目中选出与用户当前问题相关、对回答有帮助的条目。

            [知识库条目]
            ${entries}

            [用户当前问题]
            ${query}

            要求：
            1. 只输出一个 JSON 对象，格式为
            {
                "ids": []
            }
            例如：
            {
                "ids": [1, 3, 5]
            }
            2. 只选择与用户当前问题明显相关的条目；如果都不相关，输出：{"ids": []}。
            3. 只根据条目简介判断相关性。
            """.replace("${entries}", entriesText.toString().trim())
            .replace("${query}", queryText);

            ChatRequest request = new ChatRequest()
                    .loadSettings(new AISettings().copy(cubAISettings).json())
                    .setMessages(List.of(new ChatMessage().user(prompt)));

            AtomicReference<String> rawJson = new AtomicReference<>();
            chatHttpHandler.translate(UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request,
                    cubAISettings.getStream(), null,
                    (result, lastRes) -> rawJson.set(result.content()));

            List<Integer> ids = parseKnowledgeIds(rawJson.get());
            if (ids == null) {
                log.warn("知识库 cub 选择解析失败，降级为历史注入。raw={}", rawJson.get());
                return null;
            }
            return ids;
        } catch (Exception e) {
            log.error("知识库 cub 选择调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 cub 返回的文本中解析知识条目 id 列表，期望格式：{"ids": [1, 3, 5]}
     * 容错处理 ```json 代码块包裹。
     */
    private List<Integer> parseKnowledgeIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String json = raw.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<Integer>>>() {}).get("ids");
        } catch (Exception e) {
            log.warn("解析 cub 知识选择结果失败: {}", e.getMessage());
            return null;
        }
    }

    // ========================= 私有辅助方法 =========================

    /**
     * 对文本做 embedding 编码，返回向量。
     */
    private List<Float> encodeIntro(String text) {
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
            sb.append(i + 1).append(". 【").append(entry.getIntro()).append("】")
                    .append(entry.getContent()).append("\n");
        }
        return sb.toString();
    }
}