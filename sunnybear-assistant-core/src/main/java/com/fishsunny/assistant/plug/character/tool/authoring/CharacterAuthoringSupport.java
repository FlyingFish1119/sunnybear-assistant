package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 角色编写工具包的共用查找逻辑。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import org.springframework.util.StringUtils;

import java.util.List;

final class CharacterAuthoringSupport {

    private CharacterAuthoringSupport() {
    }

    /** 按 id 优先、其次按 name（忽略大小写）查找角色；都为空或未找到返回 null */
    static CharacterInfo find(CharacterInfoService service, String id, String name) {
        if (StringUtils.hasText(id)) {
            return service.findById(id.trim());
        }
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String target = name.trim();
        List<CharacterInfo> all = service.findAll();
        if (all == null) {
            return null;
        }
        for (CharacterInfo character : all) {
            if (StringUtils.hasText(character.getName()) && character.getName().equalsIgnoreCase(target)) {
                return character;
            }
        }
        return null;
    }
}
