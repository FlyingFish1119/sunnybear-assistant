package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 记忆管理控制器，提供记忆的 CRUD 接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/23
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.mvc.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;

    @Autowired
    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 列出所有记忆 */
    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<MemoryRecord> list = memoryService.getAllMemories();
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取记忆列表失败", e);
            return new RestResponse().error("获取记忆列表失败: " + e.getMessage());
        }
    }

    /** 新增或更新记忆。请求体: { id, content, mode: "add"|"update" } */
    @PostMapping("/save")
    public RestResponse save(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return new RestResponse().error("请求体不能为空");
        }
        try {
            String mode = (String) body.getOrDefault("mode", "add");
            String content = (String) body.get("content");
            Integer id = body.get("id") != null ? ((Number) body.get("id")).intValue() : null;

            if (!StringUtils.hasText(content)) {
                return new RestResponse().error("内容不能为空");
            }
            if (!"add".equalsIgnoreCase(mode) && !"update".equalsIgnoreCase(mode)) {
                return new RestResponse().error("模式仅支持 add 或 update");
            }

            MemoryRecord saved = memoryService.addOrUpdateMemory(id, content, mode);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("保存记忆失败", e);
            return new RestResponse().error("保存记忆失败: " + e.getMessage());
        }
    }

    /** 删除记忆 */
    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") Integer id) {
        if (id == null) {
            return new RestResponse().error("ID 不能为空");
        }
        try {
            MemoryRecord deleted = memoryService.deleteMemory(id);
            if (deleted == null) {
                return new RestResponse().error("记忆不存在");
            }
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除记忆失败", e);
            return new RestResponse().error("删除记忆失败: " + e.getMessage());
        }
    }
}
