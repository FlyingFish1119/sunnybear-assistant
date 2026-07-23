package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条工具包 —— 默认关闭，需显式在配置中启用
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.character.tool.glossary.enable", havingValue = "true", matchIfMissing = false)
public class CharacterGlossaryToolKit extends ToolKit {

    public CharacterGlossaryToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.glossary.enable:false}") boolean enable) {
        super(tools, enable);
    }
}
