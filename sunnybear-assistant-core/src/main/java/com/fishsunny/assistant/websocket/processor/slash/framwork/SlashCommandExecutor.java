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
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SlashCommandExecutor {

    private final Map<String, Class<? extends SlashCommandHandler>> handlers = new HashMap<>();

    @Autowired
    @SuppressWarnings("unchecked")
    public SlashCommandExecutor(List<SlashCommandHandler> handlers) {
        for (SlashCommandHandler handler : handlers) {
            SlashCommandComponent annotation = AnnotationUtils.findAnnotation(handler.getClass(), SlashCommandComponent.class);
            if (annotation == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(handler.getClass());
            this.handlers.put(annotation.value(), (Class<? extends SlashCommandHandler>) userClass);
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
            List<String> commandArray = List.of(context.originCommand().split("\\s+", 2));
            Class<? extends SlashCommandHandler> handlerClass = handlers.get(commandArray.getFirst());
            if (handlerClass == null) {
                return false;
            }
            SlashCommandHandler handler = SpringContextHolder.getBean(handlerClass);
            if (commandArray.size() == 1) {
                handler.run(context, "");
            } else {
                handler.run(context, commandArray.get(1));
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
