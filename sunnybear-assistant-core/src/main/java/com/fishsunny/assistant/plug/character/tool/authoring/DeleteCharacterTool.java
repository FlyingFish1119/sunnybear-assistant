package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 删除角色 —— 连同其会话、词条与文件一并删除，删除前必须用户确认。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.delete.enable:true}")
public class DeleteCharacterTool implements ToolHandler {

    public static final String NAME = "character_authoring_delete_tool";

    /** 确认超时（秒） */
    private static final int CONFIRM_TIMEOUT = 60;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final SecurityService securityService;

    public DeleteCharacterTool(ObjectMapper objectMapper,
                               CharacterInfoService characterInfoService,
                               SecurityService securityService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.securityService = securityService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 id 删除角色，会连同其全部会话、词条与文件一并删除，删除前需用户确认。（不可恢复，请先核对 id）")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "string", "要删除的角色 id。操作不可逆，请仔细核对。")
                ));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session"}, type = {ChatSession.class, WebSocketSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }
        if (!StringUtils.hasText(arguments.getId())) {
            throw new ToolExecutor.ToolExecuteException("参数 id 不能为空");
        }

        String id = arguments.getId().trim();
        CharacterInfo existing = characterInfoService.findById(id);
        if (existing == null) {
            return new ToolExecutor.ToolExecuteResponse(NAME, "未找到 id 为 `" + id + "` 的角色，可能已被删除。");
        }

        try {
            ask(context, existing);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("请求用户确认失败: " + e.getMessage());
        }

        try {
            CharacterInfo deleted = characterInfoService.deleteById(id);
            if (deleted == null) {
                return new ToolExecutor.ToolExecuteResponse(NAME, "未找到 id 为 `" + id + "` 的角色，可能已被删除。");
            }
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已删除角色 `" + deleted.getName() + "`（id: `" + deleted.getId() + "`）。");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("删除角色失败: " + e.getMessage());
        }
    }

    private void ask(Map<String, Object> context, CharacterInfo existing) throws Exception {
        String message = "### 角色删除确认\n\n"
                + "AI 请求删除一个角色，会连同其全部会话、词条与文件一并删除：\n\n"
                + "| 项目 | 内容 |\n"
                + "|------|------|\n"
                + "| 角色 id | " + existing.getId() + " |\n"
                + "| 角色名称 | " + existing.getName() + " |\n"
                + "\n> ⚠️ 删除不可恢复。若仍需保留，请拒绝。";
        securityService.ask(name(), message, CONFIRM_TIMEOUT, context);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    private static class Arguments {
        private String id;
    }
}
