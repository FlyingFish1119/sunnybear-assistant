package com.fishsunny.assistant.plug.world.controller;

/*
 * @Usage 世界观角色管理控制器，提供群组角色的 CRUD（id 主键）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.service.WorldCharacterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/world/character")
public class WorldCharacterController {

    private static final Logger log = LoggerFactory.getLogger(WorldCharacterController.class);

    /** 角色名称最大长度 */
    private static final int MAX_NAME_LENGTH = 50;
    /** 角色设定最大长度 */
    private static final int MAX_SETTING_LENGTH = 50000;

    private final WorldCharacterService worldCharacterService;

    @Autowired
    public WorldCharacterController(WorldCharacterService worldCharacterService) {
        this.worldCharacterService = worldCharacterService;
    }

    /** 列出指定世界观下的全部角色 */
    @RequestMapping("/list")
    public RestResponse list(@RequestParam("worldId") String worldId) {
        if (!StringUtils.hasText(worldId)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            List<WorldCharacter> list = worldCharacterService.findByWorldId(worldId);
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取角色列表失败", e);
            return new RestResponse().error("获取角色列表失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            WorldCharacter worldCharacter = worldCharacterService.findById(id);
            if (worldCharacter == null) {
                return new RestResponse().error("角色不存在");
            }
            return new RestResponse().success(worldCharacter);
        } catch (Exception e) {
            log.error("获取角色失败", e);
            return new RestResponse().error("获取角色失败: " + e.getMessage());
        }
    }

    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) WorldCharacter worldCharacter) {
        if (worldCharacter == null) {
            return new RestResponse().error("角色信息不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getWorldId())) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getName())) {
            return new RestResponse().error("角色名称不能为空");
        }
        if (worldCharacter.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("角色名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        try {
            WorldCharacter saved = worldCharacterService.save(worldCharacter);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("创建角色失败", e);
            return new RestResponse().error("创建角色失败: " + e.getMessage());
        }
    }

    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) WorldCharacter worldCharacter) {
        if (worldCharacter == null) {
            return new RestResponse().error("角色信息不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getId())) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getWorldId())) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getName())) {
            return new RestResponse().error("角色名称不能为空");
        }
        if (worldCharacter.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("角色名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        if (worldCharacter.getSetting() != null && worldCharacter.getSetting().length() > MAX_SETTING_LENGTH) {
            return new RestResponse().error("角色设定不能超过" + MAX_SETTING_LENGTH + "个字符");
        }
        try {
            WorldCharacter updated = worldCharacterService.update(worldCharacter);
            return new RestResponse().success(updated);
        } catch (Exception e) {
            log.error("更新角色失败", e);
            return new RestResponse().error("更新角色失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            worldCharacterService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除角色失败", e);
            return new RestResponse().error("删除角色失败: " + e.getMessage());
        }
    }
}
