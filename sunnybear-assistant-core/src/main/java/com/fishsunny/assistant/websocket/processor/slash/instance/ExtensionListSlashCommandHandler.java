package com.fishsunny.assistant.websocket.processor.slash.instance;

/*
 * @Usage 列出当前存在的扩展脚本（tool-extension/ 目录下的 .yaml/.yml 脚本）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/2
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptMeta;
import com.fishsunny.assistant.engine.tool.service.extension.ExtensionScriptService;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandComponent;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Slf4j
@SlashCommandComponent("/extensions")
public class ExtensionListSlashCommandHandler extends SlashCommandHandler {

    private final ChatMessageService chatMessageService;

    private final AssistantSettings assistantSettings;

    private final ExtensionScriptService extensionScriptService;

    private final ObjectMapper objectMapper;

    public ExtensionListSlashCommandHandler(ChatMessageService chatMessageService,
                                            AssistantSettings assistantSettings,
                                            ExtensionScriptService extensionScriptService,
                                            ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.assistantSettings = assistantSettings;
        this.extensionScriptService = extensionScriptService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected List<String> resolveArgs(String originArgs) {
        // /extensions 无参数，命令后附带的内容一律忽略
        return Collections.emptyList();
    }

    @Override
    protected void handle(List<String> args) throws Exception {
        List<ExtensionScriptMeta> scripts = extensionScriptService.getAvailableScripts();
        if (CollectionUtils.isEmpty(scripts)) {
            handleMessage("**当前没有可用的扩展脚本**。\n\n在 `tool-extension/` 目录下放置 `.yaml` / `.yml` 脚本文件即可自动被发现。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 🧩 当前扩展脚本（共 ").append(scripts.size()).append(" 个）\n");

        for (int i = 0; i < scripts.size(); i++) {
            ExtensionScriptMeta script = scripts.get(i);
            sb.append("\n### ").append(i + 1).append(". `").append(script.getName()).append("`\n");
            if (StringUtils.hasText(script.getDescription())) {
                sb.append("> ").append(script.getDescription()).append("\n");
            }
            sb.append("\n- **类型**: `").append(StringUtils.hasText(script.getType()) ? script.getType() : "cmd").append("`\n");

            List<ExtensionScriptMeta.Parameter> parameters = script.getParameters();
            if (!CollectionUtils.isEmpty(parameters)) {
                sb.append("- **参数**:\n");
                for (ExtensionScriptMeta.Parameter param : parameters) {
                    sb.append("  - `").append(param.getName()).append("` (").append(StringUtils.hasText(param.getType()) ? param.getType() : "string").append(param.isRequired() ? ", 必填" : ", 可选").append(")");
                    if (StringUtils.hasText(param.getDescription())) {
                        sb.append(" - ").append(param.getDescription());
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("- **参数**: 无\n");
            }

            if (StringUtils.hasText(script.getFilePath())) {
                sb.append("- **文件**: `").append(script.getFilePath()).append("`\n");
            }
        }

        handleMessage(sb.toString());
    }

    private void handleMessage(String content) {
        ChatMessage msg = new ChatMessage()
                .assistant(content, "", List.of())
                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(messages), assistantSettings.getAssistantName());
        super.insertMessage(msg, chatMessageService);
        super.sendMessage(msg, objectMapper);
        super.resultMessage.add(msg);
    }
}
