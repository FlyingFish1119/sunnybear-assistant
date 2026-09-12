package com.fishsunny.assistant.mvc.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fishsunny.assistant.plug.character.tool.battle.BattleToolKit;
import com.fishsunny.assistant.engine.tool.instance.FileToolKit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具集可见性接口的端到端契约：列表按 kit 分组给出可用性，保存能把声明排除的 kit 打开。
 * <p>
 * 刻意把设置文件指到 target/ 下：settings/ 是运行期真实配置（被 gitignore、没有本地基线），
 * 跑测试不该写它。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "engine.tool.bot.enable=false",
                "assistant.settings.base-path=target/test-settings",
                "toolkit-settings.path=target/test-settings/toolkit_settings.json"
        })
class ToolKitSettingsEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode listKits() {
        JsonNode body = restTemplate.getForObject("/settings/tools/kits", JsonNode.class);
        assertNotNull(body, "接口没有返回内容");
        assertEquals(200, body.path("status").asInt(), "接口返回失败：" + body.path("message").asText());
        return body.path("data");
    }

    private static JsonNode findKit(JsonNode kits, Class<?> kitClass) {
        String id = kitClass.getName();
        for (JsonNode kit : kits) {
            if (id.equals(kit.path("id").asText())) {
                return kit;
            }
        }
        return null;
    }

    /** 保存整表可见性，返回响应体 */
    private JsonNode saveVisibility(Map<String, Boolean> visibility) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForObject("/settings/toolkit/save",
                new HttpEntity<>(Map.of("visibility", visibility), headers), JsonNode.class);
    }

    @Test
    void listsEveryKitWithGroupedTools() {
        JsonNode kits = listKits();
        assertTrue(kits.isArray() && kits.size() >= 20, "工具集数量异常: " + kits.size());

        List<String> nameless = new ArrayList<>();
        for (JsonNode kit : kits) {
            String id = kit.path("id").asText();
            assertTrue(id.endsWith("ToolKit"), "kit id 不是全限定类名: " + id);
            if (kit.path("name").asText().isBlank()) {
                nameless.add(id);
            }
            assertTrue(kit.path("tools").isArray(), id + " 缺少工具清单");
            for (JsonNode tool : kit.path("tools")) {
                assertFalse(tool.path("name").asText().isBlank(), id + " 有工具缺名字");
            }
        }
        assertTrue(nameless.isEmpty(), "这些工具集没有展示名: " + nameless);
    }

    /** 声明开放的 kit：默认可见；声明排除的 kit：默认不可见，且两者工具都成组给出 */
    @Test
    void defaultVisibilityFollowsCodeDeclaration() {
        JsonNode kits = listKits();

        JsonNode fileKit = findKit(kits, FileToolKit.class);
        assertNotNull(fileKit, "工具集列表里没有 " + FileToolKit.class.getSimpleName());
        assertTrue(fileKit.path("defaultVisible").asBoolean(), "文件工具集应当默认可见");
        assertTrue(fileKit.path("visible").asBoolean(), "文件工具集应当默认可见");
        assertTrue(fileKit.path("tools").size() >= 5, "文件工具集的工具数偏少: " + fileKit.path("tools").size());

        JsonNode battleKit = findKit(kits, BattleToolKit.class);
        assertNotNull(battleKit, "工具集列表里没有 " + BattleToolKit.class.getSimpleName());
        assertFalse(battleKit.path("defaultVisible").asBoolean(), "战斗工具集应当默认不开放");
        assertFalse(battleKit.path("visible").asBoolean(), "战斗工具集应当默认不开放");
        assertEquals(3, battleKit.path("tools").size(), "战斗工具集应有 3 个工具");
    }

    /** 「设置了就开」：把声明排除的 kit 打开后生效，关回去后复原 */
    @Test
    void saveOverridesCodeDeclaration() {
        String battleId = BattleToolKit.class.getName();

        JsonNode saved = saveVisibility(Map.of(battleId, true));
        assertEquals(200, saved.path("status").asInt(), "保存失败：" + saved.path("message").asText());
        assertTrue(findKit(listKits(), BattleToolKit.class).path("visible").asBoolean(),
                "用户开启了声明排除的工具集，但 visible 仍是 false");

        JsonNode restored = saveVisibility(Map.of(battleId, false));
        assertEquals(200, restored.path("status").asInt());
        assertFalse(findKit(listKits(), BattleToolKit.class).path("visible").asBoolean(),
                "关回去之后 visible 应为 false");
    }

    /** 插件被移除后残留的 kit id 不该让整页存不上 */
    @Test
    void saveIgnoresUnknownKitIds() {
        JsonNode saved = saveVisibility(Map.of(
                "com.fishsunny.assistant.plug.gone.GoneToolKit", true,
                FileToolKit.class.getName(), true));

        assertEquals(200, saved.path("status").asInt(), "未知 kit 导致保存失败：" + saved.path("message").asText());
        assertTrue(findKit(listKits(), FileToolKit.class).path("visible").asBoolean());
    }
}
