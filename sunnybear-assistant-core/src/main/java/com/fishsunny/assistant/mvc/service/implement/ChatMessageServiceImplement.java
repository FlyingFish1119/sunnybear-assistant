package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage ChatMessage 业务层实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.mvc.dao.ChatMessageRepository;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.validator.ChatMessageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ChatMessageServiceImplement implements ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImplement.class);

    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageServiceImplement(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    @Transactional
    public ChatMessage save(ChatMessage chatMessage) throws Exception {
        if (! chatMessage.isCanInsert()) {
            throw new RuntimeException("消息不能插入");
        }
        ChatMessageValidator.save(chatMessage);
        chatMessage.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now());
        ChatMessage saved = chatMessageRepository.insert(chatMessage);
        fillSiblingInfo(saved);
        updateKidIndexPointer(chatMessage, saved);
        return saved;
    }

    /**
     * 新增子消息后，把父的“分支记忆箭头”指向最新子（Rule A）。
     * <p>例外（不写箭头，保持父 kid_index 为 NULL → 该层整层全亮）：
     * <ul>
     *   <li>插入的是工具结果（tool）——工具扇出不是互斥分支；</li>
     *   <li>助手子挂在“仍带工具调用”的助手父下（工具轮没走完被用户插入时的占位空助手）。</li>
     * </ul>
     */
    private void updateKidIndexPointer(ChatMessage inserted, ChatMessage saved) {
        String parentId = inserted.getParentId();
        if (!StringUtils.hasText(parentId)) {
            return;
        }
        String role = saved.getRole();
        if (ChatMessage.ROLE_TOOL.equals(role)) {
            return;
        }
        if (ChatMessage.ROLE_ASSISTANT.equals(role)) {
            ChatMessage parent = chatMessageRepository.selectById(parentId);
            if (parent != null && ChatMessage.ROLE_ASSISTANT.equals(parent.getRole())
                    && !CollectionUtils.isEmpty(parent.getToolCalls())) {
                return;
            }
        }
        chatMessageRepository.updateKidIndex(parentId, saved.getId());
    }

    /**
     * 为单条消息填充兄弟节点信息
     */
    private void fillSiblingInfo(ChatMessage msg) {
        String parentId = msg.getParentId();
        List<ChatMessage> siblings = chatMessageRepository.selectSiblingsByParentId(parentId, msg.getSessionId());
        msg.setSiblingCount(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(msg.getId())) {
                msg.setSiblingIndex(i);
                break;
            }
        }
    }

    @Override
    @Transactional
    public ChatMessage update(ChatMessage chatMessage) {
        return chatMessageRepository.update(chatMessage);
    }

    @Override
    public ChatMessage replace(ChatMessage chatMessage) throws Exception {
        return chatMessageRepository.replace(chatMessage);
    }

    @Override
    @Transactional
    public ChatMessage deleteById(String id) {
        return chatMessageRepository.deleteById(id);
    }

    @Override
    @Transactional
    public int deleteBySessionId(String sessionId) {
        return chatMessageRepository.deleteBySessionId(sessionId);
    }

    @Override
    public ChatMessage findById(String id) {
        return chatMessageRepository.selectById(id);
    }

    @Override
    public List<ChatMessage> findBySessionId(String sessionId) {
        return chatMessageRepository.selectBySessionId(sessionId);
    }

    @Override
    public List<ChatMessage> getConversationHistory(String sessionId) {
        List<ChatMessage> messages = chatMessageRepository.selectBySessionId(sessionId);
        if (messages == null) {
            return Collections.emptyList();
        }

        messages.removeIf(chatMessage -> !chatMessage.getActive());

        // 为每条消息填充兄弟节点信息
        for (ChatMessage msg : messages) {
            fillSiblingInfo(msg);
        }

        return messages;
    }

    @Override
    @Transactional
    public int switchBranch(String messageId, String direction) throws Exception {
        ChatMessage current = chatMessageRepository.selectById(messageId);
        if (current == null) {
            throw new IllegalArgumentException("消息不存在: " + messageId);
        }
        // 工具行是同一助手的扇出结果，不是可切换的分支
        if (ChatMessage.ROLE_TOOL.equals(current.getRole())) {
            return 0;
        }

        String parentId = current.getParentId();

        List<ChatMessage> siblings = chatMessageRepository.selectSiblingsByParentId(parentId, current.getSessionId());
        if (siblings.size() <= 1) {
            return 0; // 没有其他兄弟节点，无需切换
        }

        // 找到当前消息在兄弟列表中的位置
        int currentIndex = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(messageId)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex == -1) {
            return 0;
        }

        // 根据方向计算目标索引
        int targetIndex;
        if ("left".equals(direction)) {
            if (currentIndex <= 0) return 0; // 已经是第一个
            targetIndex = currentIndex - 1;
        } else if ("right".equals(direction)) {
            if (currentIndex >= siblings.size() - 1) return 0; // 已经是最后一个
            targetIndex = currentIndex + 1;
        } else {
            throw new IllegalArgumentException("无效的方向: " + direction + "，仅支持 left 或 right");
        }

        ChatMessage target = siblings.get(targetIndex);

        // 关掉离开的分支（含全部子孙）
        List<String> leaveIds = new ArrayList<>();
        leaveIds.add(messageId);
        collectDescendantIds(messageId, leaveIds);
        int affected = chatMessageRepository.batchUpdateActive(leaveIds, false);

        // 父箭头指向被选中的目标兄弟（根级父为 null：选根只靠 active，与现状一致）
        if (StringUtils.hasText(parentId)) {
            chatMessageRepository.updateKidIndex(parentId, target.getId());
        }

        // 沿记忆重亮目标子树：kid_index 非空只续该孩子，为空则整层全亮（工具扇出/线性链）；悬空指针则停
        affected += activateBranch(target);

        return affected;
    }

    /**
     * 按“记忆箭头”重亮某子树：命中目标后递归，当前节点 kid_index 非空 → 只续指向的孩子；
     * kid_index 为空 → 续全部孩子（覆盖工具扇出、单子线性链）。返回被点亮的行数。
     */
    private int activateBranch(ChatMessage node) {
        int affected = chatMessageRepository.updateActive(node.getId(), true);
        List<ChatMessage> children = chatMessageRepository.selectSiblingsByParentId(node.getId(), node.getSessionId());
        if (children.isEmpty()) {
            return affected;
        }
        String kidIndex = node.getKidIndex();
        if (StringUtils.hasText(kidIndex)) {
            for (ChatMessage child : children) {
                if (kidIndex.equals(child.getId())) {
                    affected += activateBranch(child);
                    break;
                }
            }
        } else {
            for (ChatMessage child : children) {
                affected += activateBranch(child);
            }
        }
        return affected;
    }

    @Override
    @Transactional
    public int deactivateBranch(String messageId) throws Exception {
        ChatMessage message = chatMessageRepository.selectById(messageId);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在: " + messageId);
        }

        List<String> ids = new ArrayList<>();
        collectDescendantIds(messageId, ids);
        ids.add(messageId);

        return chatMessageRepository.batchUpdateActive(ids, false);
    }

    /**
     * 递归收集某个消息的所有子孙消息 ID
     */
    private void collectDescendantIds(String parentId, List<String> collector) {
        List<ChatMessage> children = chatMessageRepository.selectSiblingsByParentId(parentId, null);
        for (ChatMessage child : children) {
            collector.add(child.getId());
            collectDescendantIds(child.getId(), collector);
        }
    }

    @Override
    @Transactional
    public void deleteUserMessageWithChildren(String id) {
        ChatMessage message = chatMessageRepository.selectById(id);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在: " + id);
        }

        List<String> ids = new ArrayList<>();
        ids.add(id);
        collectDescendantIds(id, ids);

        chatMessageRepository.deleteByIds(ids);
    }

    @Override
    @Transactional
    public void editAssistantMessage(String id, String content) {
        ChatMessage message = chatMessageRepository.selectById(id);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在: " + id);
        }
        if (!ChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new IllegalArgumentException("只能编辑助手消息，当前消息角色为: " + message.getRole());
        }

        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(content));
        message.setContents(contents);

        chatMessageRepository.update(message);
    }
}
