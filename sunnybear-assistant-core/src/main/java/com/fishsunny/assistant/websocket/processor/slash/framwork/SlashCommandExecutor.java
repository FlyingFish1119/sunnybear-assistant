package com.fishsunny.assistant.websocket.processor.slash.framwork;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/20 23:38
 */

import com.fishsunny.assistant.config.SpringContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SlashCommandExecutor {

    private final Map<String, Class<? extends SlashCommandHandler>> handlers = new HashMap<>();

    @Autowired
    public SlashCommandExecutor(List<SlashCommandHandler> handlers) {
        for (SlashCommandHandler handler : handlers) {
            SlashCommandComponent annotation = AnnotationUtils.findAnnotation(handler.getClass(), SlashCommandComponent.class);
            if (annotation == null) {
                continue;
            }
            this.handlers.put(annotation.value(), handler.getClass());
        }
    }

    private boolean isSlashCommand(String command) {
        Pattern pattern = Pattern.compile("^/[a-zA-Z]+");
        return pattern.matcher(command).find();
    }

    public boolean runSlashFactory(SlashCommandHandler.SlashCommandContext context) {
        if (! isSlashCommand(context.originCommand()))
            return false;
        try {
            String command = context.originCommand().split(" ")[0];
            Class<? extends SlashCommandHandler> handlerClass = handlers.get(command);
            if (handlerClass == null) {
                return false;
            }
            SlashCommandHandler handler = SpringContextHolder.getBean(handlerClass);
            handler.run(context);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
