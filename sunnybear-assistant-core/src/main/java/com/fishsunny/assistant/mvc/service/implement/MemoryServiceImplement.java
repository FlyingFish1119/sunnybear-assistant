package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 核心记忆服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.mvc.dao.MemoryRepository;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class MemoryServiceImplement implements MemoryService {

    public static final String MODE_ADD = "add";
    public static final String MODE_UPDATE = "update";

    private static final String AUTO_GROUP_SYSTEM_PROMPT = """
            你是记忆分组助手。你会收到一个用户的记忆列表，每行格式为「id. 内容」。
            请按语义主题把所有记忆重新划分到合适的分组，分组名用简短的中文词组（如「个人偏好」「家庭信息」「工作学习」）。
            要求：
            1. 每条记忆必须且只能归入一个分组；
            2. 相近主题的记忆放在同一分组，分组数量尽量精简；
            3. 必须覆盖输入中的每一个 id，不得遗漏；
            4. 只输出一个 JSON 对象，格式为 {"groups": {"分组名": [id, id, ...]}}，不要输出 markdown 代码块标记或任何其他文字。
            """;

    private final MemoryRepository memoryRepository;
    private final AISettings cubAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ObjectMapper objectMapper;

    public MemoryServiceImplement(MemoryRepository memoryRepository,
                                  @Qualifier(AISettings.CUB) AISettings cubAISettings,
                                  ChatHttpHandler chatHttpHandler,
                                  ObjectMapper objectMapper) {
        this.chatHttpHandler = chatHttpHandler;
        this.memoryRepository = memoryRepository;
        this.cubAISettings = cubAISettings;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryRecord addOrUpdateMemory(Integer id, String content, String groupName, String mode) {
        if (MODE_ADD.equalsIgnoreCase(mode)) {
            MemoryRecord record = new MemoryRecord()
                    .setContent(content)
                    .setGroupName(groupName);
            MemoryRecord saved = memoryRepository.insert(record);
            log.info("新增记忆: id={}, group={}", saved.getId(), saved.getGroupName());
            return saved;
        } else if (MODE_UPDATE.equalsIgnoreCase(mode)) {
            if (id == null) {
                throw new IllegalArgumentException("update 模式下 id 不能为空");
            }
            MemoryRecord existing = memoryRepository.selectById(id);
            if (existing == null) {
                throw new IllegalArgumentException("记忆不存在: id=" + id);
            }
            existing.setContent(content);
            // groupName 为空 = 调用方没带分组（如老设置页），repository 层会保持原分组不动
            existing.setGroupName(groupName);
            MemoryRecord saved = memoryRepository.update(existing);
            log.info("更新记忆: id={}, group={}", saved.getId(), saved.getGroupName());
            return saved;
        } else {
            throw new IllegalArgumentException("不支持的模式: " + mode + "，仅支持 add 和 update");
        }
    }

    @Override
    public MemoryRecord getMemoryById(Integer id) {
        if (id == null) {
            return null;
        }
        return memoryRepository.selectById(id);
    }

    @Override
    public MemoryRecord deleteMemory(Integer id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        MemoryRecord deleted = memoryRepository.deleteById(id);
        if (deleted != null) {
            log.info("删除记忆: id={}", deleted.getId());
        } else {
            log.warn("删除记忆失败，记录不存在: id={}", id);
        }
        return deleted;
    }

    @Override
    public List<MemoryRecord> getAllMemories() {
        return memoryRepository.selectAll();
    }

    @Override
    public int renameGroup(String oldName, String newName) {
        if (!StringUtils.hasText(oldName) || !StringUtils.hasText(newName)) {
            throw new IllegalArgumentException("组名不能为空");
        }
        int changed = memoryRepository.renameGroup(oldName, newName);
        log.info("分组重命名: {} -> {}（{} 条）", oldName, newName, changed);
        return changed;
    }

    @Override
    public String buildMemorySection() {
        List<MemoryRecord> memories = memoryRepository.selectAll();
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        // selectAll 已按 create_time 倒序：组的先后 = 组内最新记忆的先后，组内维持倒序
        Map<String, List<MemoryRecord>> grouped = new LinkedHashMap<>();
        for (MemoryRecord memory : memories) {
            String group = StringUtils.hasText(memory.getGroupName())
                    ? memory.getGroupName()
                    : MemoryRecord.GROUP_UNCLASSIFIED;
            grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(memory);
        }

        StringBuilder sb = new StringBuilder("\n[memory]\n");
        sb.append("以下是你和用户的产生的核心记忆：\n");
        for (Map.Entry<String, List<MemoryRecord>> entry : grouped.entrySet()) {
            sb.append("## ").append(entry.getKey()).append('\n');
            for (MemoryRecord memory : entry.getValue()) {
                sb.append(memory.getId()).append(". ").append(memory.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public List<MemoryRecord> autoGroup() {
        List<MemoryRecord> memories = memoryRepository.selectAll();
        if (memories == null || memories.isEmpty()) {
            return memories == null ? List.of() : memories;
        }

        StringBuilder userPrompt = new StringBuilder("记忆列表：\n");
        for (MemoryRecord memory : memories) {
            userPrompt.append(memory.getId()).append(". ").append(memory.getContent()).append('\n');
        }

        ChatRequest request = new ChatRequest()
                .quickJsonBuild(AUTO_GROUP_SYSTEM_PROMPT, userPrompt.toString(), cubAISettings);

        AtomicReference<String> rawContent = new AtomicReference<>();
        try {
            ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(
                    UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request);
            ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler()
                    .complete((result, lastRes) -> rawContent.set(result.content()));
            ChatHttpHandler.TranslateOption translateOption = new ChatHttpHandler.TranslateOption()
                    .setStream(cubAISettings.getStream())
                    .setEnableTTS(false);
            chatHttpHandler.translate(translateData, translateHandler, translateOption);
        } catch (Exception e) {
            throw new IllegalStateException("自动分组调用模型失败: " + e.getMessage(), e);
        }

        String json = extractJson(rawContent.get());
        if (!StringUtils.hasText(json)) {
            throw new IllegalStateException("自动分组模型返回为空");
        }

        MemoryGround ground;
        try {
            ground = objectMapper.readValue(json, MemoryGround.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析自动分组结果失败: " + e.getMessage(), e);
        }

        Map<Integer, String> idToGroup = new LinkedHashMap<>();
        if (ground != null && ground.groups() != null) {
            for (Map.Entry<String, List<Integer>> entry : ground.groups().entrySet()) {
                String group = entry.getKey() == null ? "" : entry.getKey().trim();
                if (!StringUtils.hasText(group) || entry.getValue() == null) {
                    continue;
                }
                for (Integer id : entry.getValue()) {
                    if (id != null) {
                        idToGroup.put(id, group);
                    }
                }
            }
        }

        int changed = 0;
        for (MemoryRecord memory : memories) {
            String newGroup = idToGroup.get(memory.getId());
            if (!StringUtils.hasText(newGroup) || newGroup.equals(memory.getGroupName())) {
                continue;
            }
            memoryRepository.update(new MemoryRecord()
                    .setId(memory.getId())
                    .setContent(memory.getContent())
                    .setGroupName(newGroup));
            changed++;
        }
        log.info("自动分组完成: 共 {} 条记忆，调整 {} 条", memories.size(), changed);
        return memoryRepository.selectAll();
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int left = text.indexOf('{');
        int right = text.lastIndexOf('}');
        if (left >= 0 && right > left) {
            text = text.substring(left, right + 1);
        }
        return text;
    }

    private record MemoryGround(Map<String, List<Integer>> groups) {}
}
