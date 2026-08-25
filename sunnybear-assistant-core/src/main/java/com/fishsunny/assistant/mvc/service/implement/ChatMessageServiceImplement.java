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
        return saved;
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

        // 收集当前分支和目标分支的所有子孙消息 ID
        // 被启用的列表下面的树只启用一条，而被关闭的则全部关闭
        List<String> currentBranchIds = new ArrayList<>();
        currentBranchIds.add(messageId);
        collectDescendantIds(messageId, currentBranchIds);

        List<String> targetBranchIds = new ArrayList<>();
        targetBranchIds.add(target.getId());
        collectDescendantIdsSingleBranch(target.getId(), targetBranchIds);

        // 禁用当前分支，启用目标分支
        int affected = 0;
        affected += chatMessageRepository.batchUpdateActive(currentBranchIds, false);
        affected += chatMessageRepository.batchUpdateActive(targetBranchIds, true);

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

    private void collectDescendantIdsSingleBranch(String parentId, List<String> collector) {
        List<ChatMessage> children = chatMessageRepository.selectSiblingsByParentId(parentId, null);
        if(!children.isEmpty()) {
            String childId = children.get(0).getId();
            collector.add(childId);
            collectDescendantIdsSingleBranch(childId, collector);
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
