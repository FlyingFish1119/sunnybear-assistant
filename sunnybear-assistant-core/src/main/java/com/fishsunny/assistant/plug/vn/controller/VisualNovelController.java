package com.fishsunny.assistant.plug.vn.controller;

/*
 * @Usage 视觉小说控制器：小说列表 / 单品详情 / 章节节点 / 存档读写 / 图片资源直出。
 *        底层文件读写一律委托 VisualNovelLoader，本类只做编排与数据整形。
 *
 * 约定：
 * 1. 除 /asset（二进制直出）外，统一返回 RestResponse；
 * 2. 列表接口保持轻量，不下发小说 outline（AI 大纲）与章节 content（章节剧透）；
 * 3. 章节缺失时不返回半成品数据，直接 error("章节不存在: ...")，由前端识别为「跨章自然断点」。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.plug.vn.VisualNovelLoader;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelChapter;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelData;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelSave;
import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/plug/vn")
public class VisualNovelController {

    private static final Logger log = LoggerFactory.getLogger(VisualNovelController.class);

    /** 允许直出的图片扩展名 → MediaType；其余一律 404，避免把任意文件当图片吐给浏览器 */
    private static final Map<String, MediaType> IMAGE_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "webp", MediaType.parseMediaType("image/webp"),
            "gif", MediaType.IMAGE_GIF,
            "svg", MediaType.parseMediaType("image/svg+xml"),
            "avif", MediaType.parseMediaType("image/avif"));

    /** 缩略/大图都不缓存：创作者刚生成完图就能看到，缺图 404 也不会被浏览器记住 */
    private static final CacheControl NO_CACHE = CacheControl.noStore().mustRevalidate();

    private final VisualNovelLoader loader;

    @Autowired
    public VisualNovelController(VisualNovelLoader loader) {
        this.loader = loader;
    }

    // ==================== 小说 ====================

    /**
     * 小说列表（书架）。只回前端确实要用的摘要字段，不含 outline 与章节 content。
     */
    @GetMapping("/list")
    public RestResponse list() {
        try {
            List<Map<String, Object>> novels = new ArrayList<>();
            for (String title : loader.listNovels()) {
                try {
                    novels.add(buildNovelSummary(title));
                } catch (Exception e) {
                    // 单本数据损坏不拖垮整个书架，跳过并记录
                    log.warn("读取小说 [{}] 摘要失败: {}", title, e.getMessage());
                }
            }
            return new RestResponse().success(novels);
        } catch (Exception e) {
            log.error("获取视觉小说列表失败", e);
            return new RestResponse().error("获取小说列表失败: " + e.getMessage());
        }
    }

    /**
     * 单部小说详情：data / chapters / saves / assets。
     * chapters 只给 order、title、chapterId、nodeCount，不下发 content（章节剧透）。
     */
    @GetMapping("/get")
    public RestResponse get(@RequestParam("title") String title) {
        if (!StringUtils.hasText(title)) {
            return new RestResponse().error("小说标题不能为空");
        }
        try {
            if (!loader.listNovels().contains(title)) {
                return new RestResponse().error("小说不存在: " + title);
            }
            VisualNovelData data = loader.loadData(title);
            if (data == null) {
                data = new VisualNovelData().setTitle(title);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("data", data);
            detail.put("chapters", buildChapterSummaries(title));
            detail.put("saves", buildSaveSummaries(title));
            detail.put("assets", loader.listAssets(title));
            return new RestResponse().success(detail);
        } catch (Exception e) {
            log.error("获取小说 [{}] 详情失败", title, e);
            return new RestResponse().error("获取小说详情失败: " + e.getMessage());
        }
    }

    /** 删除整部小说（连同章节、存档、资源目录） */
    @GetMapping("/delete")
    public RestResponse delete(@RequestParam("title") String title) {
        if (!StringUtils.hasText(title)) {
            return new RestResponse().error("小说标题不能为空");
        }
        try {
            boolean deleted = loader.deleteNovel(title);
            return deleted
                    ? new RestResponse().success("删除成功")
                    : new RestResponse().error("小说不存在: " + title);
        } catch (Exception e) {
            log.error("删除小说 [{}] 失败", title, e);
            return new RestResponse().error("删除小说失败: " + e.getMessage());
        }
    }

    // ==================== 章节 / 节点 ====================

    /**
     * 章节节点字典（nodeId → node）。章节不存在时回错，前端据此进入「自然断点」面板。
     */
    @GetMapping("/chapter/nodes")
    public RestResponse chapterNodes(@RequestParam("title") String title,
                                     @RequestParam("chapterId") String chapterId) {
        if (!StringUtils.hasText(title)) {
            return new RestResponse().error("小说标题不能为空");
        }
        if (!StringUtils.hasText(chapterId)) {
            return new RestResponse().error("章节 ID 不能为空");
        }
        try {
            if (!loader.listNovels().contains(title)) {
                return new RestResponse().error("小说不存在: " + title);
            }
            if (loader.loadChapter(title, chapterId) == null) {
                return new RestResponse().error("章节不存在: " + chapterId);
            }
            Map<String, VisualNovelNode> nodes = loader.loadNodes(title, chapterId);
            return new RestResponse().success(nodes);
        } catch (Exception e) {
            log.error("读取小说 [{}] 章节 [{}] 节点失败", title, chapterId, e);
            return new RestResponse().error("读取章节节点失败: " + e.getMessage());
        }
    }

    // ==================== 存档 ====================

    @GetMapping("/save/list")
    public RestResponse saveList(@RequestParam("title") String title) {
        if (!StringUtils.hasText(title)) {
            return new RestResponse().error("小说标题不能为空");
        }
        try {
            return new RestResponse().success(buildSaveSummaries(title));
        } catch (Exception e) {
            log.error("读取小说 [{}] 存档列表失败", title, e);
            return new RestResponse().error("读取存档列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/save/get")
    public RestResponse saveGet(@RequestParam("title") String title,
                                @RequestParam("name") String name) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(name)) {
            return new RestResponse().error("小说标题与存档名不能为空");
        }
        try {
            VisualNovelSave save = loader.loadSave(title, name);
            if (save == null) {
                return new RestResponse().error("存档不存在: " + name);
            }
            return new RestResponse().success(save);
        } catch (Exception e) {
            log.error("读取存档 [{}] 失败", name, e);
            return new RestResponse().error("读取存档失败: " + e.getMessage());
        }
    }

    @PostMapping("/save/write")
    public RestResponse saveWrite(@RequestParam("title") String title,
                                  @RequestParam("name") String name,
                                  @RequestBody(required = false) VisualNovelSave save) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(name)) {
            return new RestResponse().error("小说标题与存档名不能为空");
        }
        if (save == null) {
            return new RestResponse().error("存档内容不能为空");
        }
        try {
            if (!StringUtils.hasText(save.getCurrentChapter()) || !StringUtils.hasText(save.getCurrentNodeId())) {
                return new RestResponse().error("存档缺少当前章节或当前节点");
            }
            if (save.getEventMap() == null) {
                save.setEventMap(new LinkedHashMap<>());
            }
            loader.writeSave(title, name, save);
            return new RestResponse().success("保存成功");
        } catch (Exception e) {
            log.error("写入存档 [{}] 失败", name, e);
            return new RestResponse().error("保存存档失败: " + e.getMessage());
        }
    }

    @GetMapping("/save/delete")
    public RestResponse saveDelete(@RequestParam("title") String title,
                                   @RequestParam("name") String name) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(name)) {
            return new RestResponse().error("小说标题与存档名不能为空");
        }
        try {
            boolean deleted = loader.deleteSave(title, name);
            return deleted
                    ? new RestResponse().success("删除成功")
                    : new RestResponse().error("存档不存在: " + name);
        } catch (Exception e) {
            log.error("删除存档 [{}] 失败", name, e);
            return new RestResponse().error("删除存档失败: " + e.getMessage());
        }
    }

    // ==================== 资源直出 ====================

    /**
     * 图片直出。文件不存在 / 非普通文件 / 0 字节 / 非法路径 一律 404，
     * 由前端 @error 统一接管渲染占位卡片（0 字节占位图放行会让 <img> 裂图）。
     */
    @GetMapping("/asset")
    public ResponseEntity<Resource> asset(@RequestParam("title") String title,
                                          @RequestParam("name") String name) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(name)) {
            return ResponseEntity.notFound().build();
        }
        Path path;
        try {
            // resolveAssetPath 内部已做单层名校验 + normalize + startsWith 校验，杜绝 ..
            path = loader.resolveAssetPath(title, name);
        } catch (Exception e) {
            log.warn("非法资源路径: title=[{}] name=[{}] ({})", title, name, e.getMessage());
            return ResponseEntity.notFound().build();
        }
        try {
            if (!Files.isRegularFile(path) || Files.size(path) == 0) {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.warn("读取资源信息失败: {} ({})", path, e.getMessage());
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = mediaTypeOf(path.getFileName().toString());
        if (mediaType == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(NO_CACHE)
                .body(new FileSystemResource(path));
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> buildNovelSummary(String title) {
        VisualNovelData data = loader.loadData(title);
        if (data == null) {
            data = new VisualNovelData().setTitle(title);
        }
        List<VisualNovelChapter> chapters = loader.listChapters(title);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", StringUtils.hasText(data.getTitle()) ? data.getTitle() : title);
        summary.put("desc", data.getDesc());
        summary.put("coverImg", data.getCoverImg());
        summary.put("startNodeId", data.getStartNodeId());
        summary.put("chapterCount", chapters.size());
        summary.put("firstChapterId", chapters.isEmpty() ? null : chapters.get(0).buildChapterId());
        summary.put("saveCount", loader.listSaves(title).size());
        summary.put("assetCount", loader.listAssets(title).size());
        return summary;
    }

    private List<Map<String, Object>> buildChapterSummaries(String title) {
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (VisualNovelChapter chapter : loader.listChapters(title)) {
            String chapterId = chapter.buildChapterId();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", chapter.getOrder());
            item.put("title", chapter.getTitle());
            item.put("chapterId", chapterId);
            item.put("nodeCount", loader.loadNodes(title, chapterId).size());
            chapters.add(item);
        }
        return chapters;
    }

    private List<Map<String, Object>> buildSaveSummaries(String title) {
        List<Map<String, Object>> saves = new ArrayList<>();
        for (String name : loader.listSaves(title)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            VisualNovelSave save = loader.loadSave(title, name);
            if (save == null) {
                // 空文件 / 解析不出内容：仍占位，前端显示为「空存档」
                item.put("currentChapter", null);
                item.put("currentNodeId", null);
                item.put("eventMap", Map.of());
            } else {
                item.put("currentChapter", save.getCurrentChapter());
                item.put("currentNodeId", save.getCurrentNodeId());
                item.put("eventMap", save.getEventMap() == null ? Map.of() : save.getEventMap());
            }
            saves.add(item);
        }
        return saves;
    }

    /** 按扩展名取图片 MediaType；不在白名单返回 null */
    private MediaType mediaTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return IMAGE_TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
