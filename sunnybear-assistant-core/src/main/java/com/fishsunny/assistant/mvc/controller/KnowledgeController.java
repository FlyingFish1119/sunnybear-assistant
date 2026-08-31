package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 知识库管理控制器，提供知识条目的 CRUD 接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/23
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;

    @Autowired
    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 列出所有知识条目 */
    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<KnowledgeRecord> list = knowledgeService.getAllKnowledge();
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取知识库列表失败", e);
            return new RestResponse().error("获取知识库列表失败: " + e.getMessage());
        }
    }

    /** 根据 ID 获取单条知识条目 */
    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") Integer id) {
        if (id == null) {
            return new RestResponse().error("ID 不能为空");
        }
        try {
            KnowledgeRecord record = knowledgeService.getKnowledgeById(id);
            if (record == null) {
                return new RestResponse().error("知识条目不存在");
            }
            return new RestResponse().success(record);
        } catch (Exception e) {
            log.error("获取知识条目失败", e);
            return new RestResponse().error("获取知识条目失败: " + e.getMessage());
        }
    }

    /** 新增或更新知识条目。请求体: { id, intro, content, mode: "add"|"update" } */
    @PostMapping("/save")
    public RestResponse save(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return new RestResponse().error("请求体不能为空");
        }
        try {
            String mode = (String) body.getOrDefault("mode", "add");
            String intro = (String) body.get("intro");
            String content = (String) body.get("content");
            Integer id = body.get("id") != null ? ((Number) body.get("id")).intValue() : null;

            if (!StringUtils.hasText(intro)) {
                return new RestResponse().error("简介不能为空");
            }
            if (!StringUtils.hasText(content)) {
                return new RestResponse().error("内容不能为空");
            }
            if (!"add".equalsIgnoreCase(mode) && !"update".equalsIgnoreCase(mode)) {
                return new RestResponse().error("模式仅支持 add 或 update");
            }

            KnowledgeRecord saved = knowledgeService.addOrUpdateKnowledge(id, intro, content, mode);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("保存知识条目失败", e);
            return new RestResponse().error("保存知识条目失败: " + e.getMessage());
        }
    }

    /** 删除知识条目 */
    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") Integer id) {
        if (id == null) {
            return new RestResponse().error("ID 不能为空");
        }
        try {
            KnowledgeRecord deleted = knowledgeService.deleteKnowledge(id);
            if (deleted == null) {
                return new RestResponse().error("知识条目不存在");
            }
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除知识条目失败", e);
            return new RestResponse().error("删除知识条目失败: " + e.getMessage());
        }
    }

    /** 查询某会话已注入的知识条目列表 */
    @RequestMapping("/session/list")
    public RestResponse sessionList(@RequestParam("sessionId") String sessionId) {
        try {
            List<KnowledgeRecord> list = knowledgeService.listSessionKnowledge(sessionId);
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("查询会话知识库失败: {}", e.getMessage(), e);
            return new RestResponse().error("查询会话知识库失败: " + e.getMessage());
        }
    }

    /** 从会话注入列表中移除一条知识条目（不影响知识条目本身） */
    @RequestMapping("/session/remove")
    public RestResponse sessionRemove(@RequestParam("sessionId") String sessionId,
                                      @RequestParam("knowledgeId") Integer knowledgeId) {
        try {
            boolean removed = knowledgeService.removeSessionKnowledge(sessionId, knowledgeId);
            return new RestResponse().success(removed);
        } catch (Exception e) {
            log.error("移除会话知识失败: {}", e.getMessage(), e);
            return new RestResponse().error("移除会话知识失败: " + e.getMessage());
        }
    }

    /** 清空某会话的全部知识注入记录 */
    @RequestMapping("/session/clear")
    public RestResponse sessionClear(@RequestParam("sessionId") String sessionId) {
        try {
            knowledgeService.clearSessionKnowledge(sessionId);
            return new RestResponse().success(true);
        } catch (Exception e) {
            log.error("清空会话知识失败: {}", e.getMessage(), e);
            return new RestResponse().error("清空会话知识失败: " + e.getMessage());
        }
    }
}
