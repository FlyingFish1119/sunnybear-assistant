package com.fishsunny.assistant.mvc.service.validator;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 03:47
 */

import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.variable.RoleVariable;
import org.springframework.util.StringUtils;

public class ChatMessageValidator {

    public static void save(ChatMessage chatMessage) throws UserException {
        validateObject(chatMessage);
        validateSessionId(chatMessage.getSessionId());
        validateRole(chatMessage.getRole());
        validateName(chatMessage.getName());
    }

    private static void validateObject(ChatMessage chatMessage) throws UserException {
        if (chatMessage == null) {
            throw new UserException("聊天消息不能为空");
        }
    }

    private static void validateSessionId(String sessionId) throws UserException {
        if (!StringUtils.hasText(sessionId)) {
            throw new UserException("会话 ID 不能为空");
        }
    }

    private static void validateRole(String role) throws UserException {
        if (!StringUtils.hasText(role)) {
            throw new UserException("角色不能为空");
        }
        switch (role) {
            case RoleVariable.ROLE_SYSTEM:
            case RoleVariable.ROLE_USER:
            case RoleVariable.ROLE_ASSISTANT:
            case RoleVariable.ROLE_TOOL:
                break;
            default:
                throw new UserException("角色[" + role + "]无效");
        }
    }

    private static void validateName(String name) throws UserException {
        if (!StringUtils.hasText(name)) {
            throw new UserException("名称不能为空");
        }
    }
}
