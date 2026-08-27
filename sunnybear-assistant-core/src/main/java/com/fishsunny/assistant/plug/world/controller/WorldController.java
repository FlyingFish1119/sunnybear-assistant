package com.fishsunny.assistant.plug.world.controller;

/*
 * @Usage 世界观管理控制器，提供世界观的 CRUD、背景图上传/删除等功能
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.plug.world.dto.WorldFileData;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;
import com.fishsunny.assistant.plug.world.service.WorldImportExportService;
import com.fishsunny.assistant.plug.world.service.WorldInfoService;
import com.fishsunny.assistant.plug.world.service.WorldSessionMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/world")
public class WorldController {

    private static final Logger log = LoggerFactory.getLogger(WorldController.class);

    /** 世界观名称最大长度 */
    private static final int MAX_NAME_LENGTH = 50;

    private final WorldInfoService worldInfoService;
    private final WorldSessionMappingService mappingService;
    private final ChatSessionService chatSessionService;
    private final WorldImportExportService importExportService;

    @Autowired
    public WorldController(WorldInfoService worldInfoService,
                           WorldSessionMappingService mappingService,
                           ChatSessionService chatSessionService,
                           WorldImportExportService importExportService) {
        this.worldInfoService = worldInfoService;
        this.mappingService = mappingService;
        this.chatSessionService = chatSessionService;
        this.importExportService = importExportService;
    }

    // ==================== 导入导出 ====================

    /**
     * 导出世界观为 JSON 文件数据（不含头像/背景图/ID，知识知晓角色按名称引用）。
     */
    @RequestMapping("/export")
    public RestResponse export(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            WorldFileData data = importExportService.exportWorld(id);
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.error("导出世界观失败", e);
            return new RestResponse().error("导出世界观失败: " + e.getMessage());
        }
    }

    /**
     * 导入世界观 JSON：targetWorldId 为空 = 新建世界观；否则覆盖该世界观（替换其角色与知识，保留背景图和会话绑定）。
     */
    @PostMapping("/import")
    public RestResponse importWorld(@RequestParam(value = "targetWorldId", required = false) String targetWorldId,
                                    @RequestBody(required = false) WorldFileData data) {
        try {
            WorldInfo imported = importExportService.importWorld(data, targetWorldId);
            return new RestResponse().success(imported);
        } catch (Exception e) {
            log.error("导入世界观失败", e);
            return new RestResponse().error("导入世界观失败: " + e.getMessage());
        }
    }

    // ==================== 世界观 CRUD ====================

    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<WorldInfo> worlds = worldInfoService.findAll();
            return new RestResponse().success(worlds);
        } catch (Exception e) {
            log.error("获取世界观列表失败", e);
            return new RestResponse().error("获取世界观列表失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            WorldInfo worldInfo = worldInfoService.findById(id);
            if (worldInfo == null) {
                return new RestResponse().error("世界观不存在");
            }
            return new RestResponse().success(worldInfo);
        } catch (Exception e) {
            log.error("获取世界观失败", e);
            return new RestResponse().error("获取世界观失败: " + e.getMessage());
        }
    }

    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) WorldInfo worldInfo) {
        if (worldInfo == null) {
            return new RestResponse().error("世界观信息不能为空");
        }
        if (!StringUtils.hasText(worldInfo.getName())) {
            return new RestResponse().error("世界观名称不能为空");
        }
        if (worldInfo.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("世界观名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        try {
            WorldInfo saved = worldInfoService.save(worldInfo);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("创建世界观失败", e);
            return new RestResponse().error("创建世界观失败: " + e.getMessage());
        }
    }

    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) WorldInfo worldInfo) {
        if (worldInfo == null) {
            return new RestResponse().error("世界观信息不能为空");
        }
        if (!StringUtils.hasText(worldInfo.getId())) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(worldInfo.getName())) {
            return new RestResponse().error("世界观名称不能为空");
        }
        if (worldInfo.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("世界观名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        try {
            WorldInfo existing = worldInfoService.findById(worldInfo.getId());
            if (existing == null) {
                return new RestResponse().error("世界观不存在");
            }
            WorldInfo updated = worldInfoService.update(worldInfo);
            return new RestResponse().success(updated);
        } catch (Exception e) {
            log.error("更新世界观失败", e);
            return new RestResponse().error("更新世界观失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            worldInfoService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除世界观失败", e);
            return new RestResponse().error("删除世界观失败: " + e.getMessage());
        }
    }

    /**
     * 单独删除世界观背景图（文件 + 清空 DB 字段）。
     */
    @RequestMapping("/delete-background")
    public RestResponse deleteBackground(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            worldInfoService.deleteBackground(id);
            return new RestResponse().success("背景图已删除");
        } catch (Exception e) {
            log.error("删除世界观背景图失败", e);
            return new RestResponse().error("删除背景图失败: " + e.getMessage());
        }
    }

    /**
     * 单独上传世界观背景图（multipart 文件上传，与 create/update 解耦）。
     */
    @PostMapping("/upload-background")
    public RestResponse uploadBackground(@RequestParam("id") String id,
                                         @RequestParam("file") MultipartFile file) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        try {
            String path = worldInfoService.uploadBackground(id, file);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传世界观背景图失败", e);
            return new RestResponse().error("上传背景图失败: " + e.getMessage());
        }
    }

    // ==================== 会话绑定 ====================

    /** 通过会话 ID 获取绑定的世界观 */
    @RequestMapping("/get-by-session")
    public RestResponse getWorldBySessionId(@RequestParam("sessionId") String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            WorldSessionMapping mapping = mappingService.findBySessionId(sessionId);
            if (mapping == null) {
                return new RestResponse().success(null);
            }
            WorldInfo world = worldInfoService.findById(mapping.getWorldId());
            return new RestResponse().success(world);
        } catch (Exception e) {
            log.error("通过会话获取世界观失败", e);
            return new RestResponse().error("获取世界观失败: " + e.getMessage());
        }
    }

    /** 绑定群聊会话到世界观 */
    @PostMapping("/bind-session")
    public RestResponse bindSession(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) {
            return new RestResponse().error("参数不能为空");
        }
        String sessionId = body.get("sessionId");
        String worldId = body.get("worldId");
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        if (!StringUtils.hasText(worldId)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            WorldSessionMapping mapping = mappingService.createMapping(sessionId, worldId);
            return new RestResponse().success(mapping);
        } catch (Exception e) {
            log.error("绑定会话失败", e);
            return new RestResponse().error("绑定会话失败: " + e.getMessage());
        }
    }

    /** 解绑群聊会话与世界观的映射 */
    @RequestMapping("/unbind-session")
    public RestResponse unbindSession(@RequestParam("sessionId") String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            mappingService.deleteBySessionId(sessionId);
            return new RestResponse().success("解绑成功");
        } catch (Exception e) {
            log.error("解绑会话失败", e);
            return new RestResponse().error("解绑会话失败: " + e.getMessage());
        }
    }

    /** 列出绑定到某世界观的全部群聊会话 */
    @RequestMapping("/sessions")
    public RestResponse getSessionsByWorldId(@RequestParam("worldId") String worldId) {
        if (!StringUtils.hasText(worldId)) {
            return new RestResponse().error("世界观 ID 不能为空");
        }
        try {
            List<String> sessionIds = mappingService.findSessionIdsByWorldId(worldId);
            List<ChatSession> sessions = new ArrayList<>();
            for (String sessionId : sessionIds) {
                try {
                    ChatSession session = chatSessionService.findById(sessionId);
                    if (session != null) {
                        sessions.add(session);
                    }
                } catch (Exception e) {
                    log.warn("会话 [{}] 不存在，跳过", sessionId);
                }
            }
            return new RestResponse().success(sessions);
        } catch (Exception e) {
            log.error("获取世界观会话失败", e);
            return new RestResponse().error("获取世界观会话失败: " + e.getMessage());
        }
    }
}
