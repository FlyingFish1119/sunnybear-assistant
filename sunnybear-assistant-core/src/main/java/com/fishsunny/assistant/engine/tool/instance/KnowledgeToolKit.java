package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 知识库工具包
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.knowledge.enable", havingValue = "true", matchIfMissing = true)
public class KnowledgeToolKit extends ToolKit {

    public KnowledgeToolKit(List<ToolHandler> tools, @Value("${engine.tool.knowledge.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
