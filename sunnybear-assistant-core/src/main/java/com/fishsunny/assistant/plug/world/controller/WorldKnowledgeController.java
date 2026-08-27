package com.fishsunny.assistant.plug.world.controller;

/*
 * @Usage 世界观知识管理控制器，提供知识的 CRUD
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.plug.world.service.WorldKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/world/knowledge")
public class WorldKnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(WorldKnowledgeController.class);

    /** 知识标题最大长度 */
    private static final int MAX_TITLE_LENGTH = 200;
    /** 知识内容最大长度 */
    private static final int MAX_CONTENT_LENGTH = 50000;

    private final WorldKnowledgeService worldKnowledgeService;

    @Autowired
    public WorldKnowledgeController(WorldKnowledgeService worldKnowledgeService) {
        this.worldKnowledgeService = worldKnowledgeService;
    }

    /** 列出指定世界观下的全部知识 */
    @RequestMapping("/list")
    public RestResponse list(@RequestParam("worldId") String worldId) {
        if (!StringUtils.hasText(worldId)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            List<WorldKnowledge> list = worldKnowledgeService.findByWorldId(worldId);
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取知识列表失败", e);
            return new RestResponse().error("获取知识列表失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("知识 ID 不能为空");
        }
        try {
            WorldKnowledge knowledge = worldKnowledgeService.findById(id);
            if (knowledge == null) {
                return new RestResponse().error("知识不存在");
            }
            return new RestResponse().success(knowledge);
        } catch (Exception e) {
            log.error("获取知识失败", e);
            return new RestResponse().error("获取知识失败: " + e.getMessage());
        }
    }

    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) WorldKnowledge knowledge) {
        if (knowledge == null) {
            return new RestResponse().error("知识信息不能为空");
        }
        if (!StringUtils.hasText(knowledge.getWorldId())) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getTitle())) {
            return new RestResponse().error("知识标题不能为空");
        }
        if (knowledge.getTitle().length() > MAX_TITLE_LENGTH) {
            return new RestResponse().error("知识标题不能超过" + MAX_TITLE_LENGTH + "个字符");
        }
        if (knowledge.getContent() != null && knowledge.getContent().length() > MAX_CONTENT_LENGTH) {
            return new RestResponse().error("知识内容不能超过" + MAX_CONTENT_LENGTH + "个字符");
        }
        try {
            WorldKnowledge saved = worldKnowledgeService.save(knowledge);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("创建知识失败", e);
            return new RestResponse().error("创建知识失败: " + e.getMessage());
        }
    }

    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) WorldKnowledge knowledge) {
        if (knowledge == null) {
            return new RestResponse().error("知识信息不能为空");
        }
        if (!StringUtils.hasText(knowledge.getId())) {
            return new RestResponse().error("知识 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getWorldId())) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getTitle())) {
            return new RestResponse().error("知识标题不能为空");
        }
        if (knowledge.getTitle().length() > MAX_TITLE_LENGTH) {
            return new RestResponse().error("知识标题不能超过" + MAX_TITLE_LENGTH + "个字符");
        }
        if (knowledge.getContent() != null && knowledge.getContent().length() > MAX_CONTENT_LENGTH) {
            return new RestResponse().error("知识内容不能超过" + MAX_CONTENT_LENGTH + "个字符");
        }
        try {
            WorldKnowledge updated = worldKnowledgeService.update(knowledge);
            return new RestResponse().success(updated);
        } catch (Exception e) {
            log.error("更新知识失败", e);
            return new RestResponse().error("更新知识失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("知识 ID 不能为空");
        }
        try {
            worldKnowledgeService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除知识失败", e);
            return new RestResponse().error("删除知识失败: " + e.getMessage());
        }
    }
}
