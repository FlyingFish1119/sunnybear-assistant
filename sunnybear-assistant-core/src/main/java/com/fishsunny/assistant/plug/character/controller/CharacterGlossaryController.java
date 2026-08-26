package com.fishsunny.assistant.plug.character.controller;

/*
 * @Usage 角色词条管理控制器，提供词条的 CRUD 接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/character/glossary")
public class CharacterGlossaryController {

    private static final Logger log = LoggerFactory.getLogger(CharacterGlossaryController.class);

    private final CharacterGlossaryService glossaryService;

    @Autowired
    public CharacterGlossaryController(CharacterGlossaryService glossaryService) {
        this.glossaryService = glossaryService;
    }

    /** 列出指定角色的全部词条 */
    @RequestMapping("/list")
    public RestResponse list(@RequestParam("characterId") String characterId) {
        if (!StringUtils.hasText(characterId)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            List<CharacterGlossary> list = glossaryService.listByCharacterId(characterId);
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取词条列表失败", e);
            return new RestResponse().error("获取词条列表失败: " + e.getMessage());
        }
    }

    /** 按角色 + 关键词获取单条词条 */
    @RequestMapping("/get")
    public RestResponse get(@RequestParam("characterId") String characterId,
                            @RequestParam("keyword") String keyword) {
        if (!StringUtils.hasText(characterId)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(keyword)) {
            return new RestResponse().error("关键词不能为空");
        }
        try {
            CharacterGlossary glossary = glossaryService.getByCharacterIdAndKeyword(characterId, keyword);
            if (glossary == null) {
                return new RestResponse().success(null);
            }
            return new RestResponse().success(glossary);
        } catch (Exception e) {
            log.error("获取词条失败", e);
            return new RestResponse().error("获取词条失败: " + e.getMessage());
        }
    }

    /** 新增词条 */
    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) CharacterGlossary glossary) {
        if (glossary == null) {
            return new RestResponse().error("词条信息不能为空");
        }
        try {
            CharacterGlossary saved = glossaryService.create(glossary);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("创建词条失败", e);
            return new RestResponse().error("创建词条失败: " + e.getMessage());
        }
    }

    /** 更新词条 */
    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) CharacterGlossary glossary) {
        if (glossary == null) {
            return new RestResponse().error("词条信息不能为空");
        }
        if (glossary.getId() == null) {
            return new RestResponse().error("词条 ID 不能为空");
        }
        try {
            CharacterGlossary updated = glossaryService.update(glossary);
            return new RestResponse().success(updated);
        } catch (Exception e) {
            log.error("更新词条失败", e);
            return new RestResponse().error("更新词条失败: " + e.getMessage());
        }
    }

    /** 删除词条 */
    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") Long id) {
        if (id == null) {
            return new RestResponse().error("词条 ID 不能为空");
        }
        try {
            glossaryService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除词条失败", e);
            return new RestResponse().error("删除词条失败: " + e.getMessage());
        }
    }

    /** 批量导入词条（JSON 数组，关键词重复的条目覆盖更新） */
    @PostMapping("/import")
    public RestResponse importGlossaries(@RequestParam("characterId") String characterId,
                                         @RequestBody(required = false) List<CharacterGlossary> items) {
        if (!StringUtils.hasText(characterId)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        if (items == null || items.isEmpty()) {
            return new RestResponse().error("导入数据不能为空");
        }
        try {
            return new RestResponse().success(glossaryService.importByCharacterId(characterId, items));
        } catch (Exception e) {
            log.error("导入词条失败", e);
            return new RestResponse().error("导入词条失败: " + e.getMessage());
        }
    }
}
