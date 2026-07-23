package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 核心记忆服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.mvc.dao.MemoryRepository;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.mvc.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryServiceImplement implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImplement.class);

    public static final String MODE_ADD = "add";
    public static final String MODE_UPDATE = "update";

    private final MemoryRepository memoryRepository;

    public MemoryServiceImplement(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public MemoryRecord addOrUpdateMemory(Integer id, String content, String mode) {
        if (MODE_ADD.equalsIgnoreCase(mode)) {
            MemoryRecord record = new MemoryRecord()
                    .setContent(content);
            MemoryRecord saved = memoryRepository.insert(record);
            log.info("新增记忆: id={}", saved.getId());
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
            MemoryRecord saved = memoryRepository.update(existing);
            log.info("更新记忆: id={}", saved.getId());
            return saved;
        } else {
            throw new IllegalArgumentException("不支持的模式: " + mode + "，仅支持 add 和 update");
        }
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
    public String buildMemorySection() {
        List<MemoryRecord> memories = memoryRepository.selectAll();
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n[memory]\n");
        sb.append("以下是你和用户的产生的核心记忆：\n");
        for (MemoryRecord memory : memories) {
            sb.append(memory.getId()).append(". ").append(memory.getContent()).append("\n");
        }
        return sb.toString();
    }
}
