package com.fishsunny.assistant.plug.vn;

/*
 * @Usage
 * 视觉小说（VN）目录级读写入口，所有落盘结构集中在此维护：
 * {basePath}/{title}/data.json
 * {basePath}/{title}/assets/<图片扁平存放>
 * {basePath}/{title}/save/<saveName>.json
 * {basePath}/{title}/{order}-{chapterTitle}/chapter.md + nodes.json
 *
 * 约定：
 * 1. 文件不存在 / 0 字节 / 纯空白，一律按「空」处理，不抛异常；
 * 2. 写入前自动补齐父目录与约定子目录（assets、save），可重复执行；
 * 3. 序列化使用本类私有的 ObjectMapper 副本：忽略 null 字段 + 缩进美化，不污染全局 mapper；
 * 4. 节点集合统一按 JSON 中的顺序落盘读取（LinkedHashMap），保证 AI 看到的内容有序。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 10:43
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelChapter;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelData;
import com.fishsunny.assistant.plug.vn.entity.VisualNovelSave;
import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class VisualNovelLoader {

    private static final String DATA = "data.json";

    private static final String NODES = "nodes.json";

    private static final String CHAPTER_MD = "chapter.md";

    private static final String SAVE_DIR = "save";

    private static final String ASSETS_DIR = "assets";

    private static final String JSON_SUFFIX = ".json";

    private static final String EMPTY_JSON_OBJECT = "{}";

    private static final String EMPTY_TEXT = "";

    private final Path basePath;

    private final ObjectMapper objectMapper;

    public VisualNovelLoader(@Value("${plug.file-path:data/plugins/}") String basePath, ObjectMapper objectMapper) {
        this.basePath = Path.of(basePath, "vn");
        ObjectMapper vnMapper = objectMapper.copy();
        // 忽略 null 字段（节省 AI 上下文）+ 缩进美化，只作用于 VN 的落盘读写
        vnMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        vnMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 固定缩进 2 空格、换行固定 LF、键值间不留多余空格，保证跨平台落盘结果一致
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        vnMapper.setDefaultPrettyPrinter(printer.withSeparators(
                Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)));
        this.objectMapper = vnMapper;
    }

    // ============================ 小说（Data） ============================

    public List<String> listNovels() {
        if (! Files.isDirectory(basePath)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(basePath)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取小说列表失败: " + basePath, e);
        }
    }

    public VisualNovelData loadData(String title) {
        Path path = novelDir(title).resolve(DATA);
        if (! Files.isRegularFile(path)) {
            return null;
        }
        String json = readText(path);
        return json == null ? new VisualNovelData().setTitle(title) : readJson(json, VisualNovelData.class);
    }

    public void saveData(String title, VisualNovelData data) {
        Path dir = novelDir(title);
        ensureDir(dir);
        ensureDir(dir.resolve(ASSETS_DIR));
        ensureDir(dir.resolve(SAVE_DIR));
        VisualNovelData target = data == null ? new VisualNovelData() : data;
        if (target.getTitle() == null || target.getTitle().isBlank()) {
            target.setTitle(title);
        }
        writeJson(dir.resolve(DATA), target);
    }

    public boolean deleteNovel(String title) {
        return deleteRecursively(novelDir(title));
    }

    // ============================ 章节（Chapter） ============================

    public List<VisualNovelChapter> listChapters(String title) {
        Path dir = novelDir(title);
        if (! Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> ! SAVE_DIR.equals(name) && ! ASSETS_DIR.equals(name))
                    .map(this::parseChapter)
                    .filter(Objects::nonNull)
                    .map(chapter -> chapter.setContent(readChapterContent(dir.resolve(chapter.buildChapterId()))))
                    .sorted(Comparator.comparing(VisualNovelChapter::getOrder))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取章节列表失败: " + dir, e);
        }
    }

    public VisualNovelChapter loadChapter(String title, String chapterId) {
        VisualNovelChapter chapter = chapterId == null ? null : parseChapter(chapterId);
        if (chapter == null) {
            return null;
        }
        Path dir = chapterDir(title, chapterId);
        return Files.isDirectory(dir) ? chapter.setContent(readChapterContent(dir)) : null;
    }

    public void saveChapter(String title, VisualNovelChapter chapter) {
        if (chapter == null || chapter.getOrder() == null || chapter.getTitle() == null) {
            throw new IllegalArgumentException("chapter 的 order / title 不能为空");
        }
        Path dir = chapterDir(title, chapter.buildChapterId());
        ensureDir(dir);
        writeText(dir.resolve(CHAPTER_MD), chapter.getContent() == null ? EMPTY_TEXT : chapter.getContent());
        Path nodes = dir.resolve(NODES);
        if (! Files.isRegularFile(nodes) || isEmptyFile(nodes)) {
            writeJson(nodes, new LinkedHashMap<String, VisualNovelNode>());
        }
    }

    public boolean deleteChapter(String title, String chapterId) {
        return deleteRecursively(chapterDir(title, chapterId));
    }

    // ============================ 节点（Nodes） ============================

    public Map<String, VisualNovelNode> loadNodes(String title, String chapterId) {
        String json = readText(chapterDir(title, chapterId).resolve(NODES));
        if (json == null) {
            return new LinkedHashMap<>();
        }
        JavaType type = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, VisualNovelNode.class);
        Map<String, VisualNovelNode> nodes = readJson(json, type);
        return nodes == null ? new LinkedHashMap<>() : nodes;
    }

    public VisualNovelNode loadNode(String title, String chapterId, String nodeId) {
        return nodeId == null ? null : loadNodes(title, chapterId).get(nodeId);
    }

    public void saveNodes(String title, String chapterId, Map<String, VisualNovelNode> nodes) {
        Path dir = chapterDir(title, chapterId);
        ensureDir(dir);
        writeJson(dir.resolve(NODES), nodes == null ? new LinkedHashMap<String, VisualNovelNode>() : nodes);
    }

    // ============================ 存档（Save） ============================

    public List<String> listSaves(String title) {
        Path dir = novelDir(title).resolve(SAVE_DIR);
        if (! Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(JSON_SUFFIX))
                    .map(name -> name.substring(0, name.length() - JSON_SUFFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取存档列表失败: " + dir, e);
        }
    }

    public VisualNovelSave loadSave(String title, String saveName) {
        Path path = savePath(title, saveName);
        if (! Files.isRegularFile(path)) {
            return null;
        }
        String json = readText(path);
        return json == null ? new VisualNovelSave() : readJson(json, VisualNovelSave.class);
    }

    public void writeSave(String title, String saveName, VisualNovelSave save) {
        writeJson(savePath(title, saveName), save == null ? new VisualNovelSave() : save);
    }

    public boolean deleteSave(String title, String saveName) {
        try {
            return Files.deleteIfExists(savePath(title, saveName));
        } catch (IOException e) {
            throw new UncheckedIOException("删除存档失败: " + saveName, e);
        }
    }

    // ============================ 图片资源（Assets） ============================

    public Path getAssetsDir(String title) {
        return novelDir(title).resolve(ASSETS_DIR);
    }

    public Path resolveAssetPath(String title, String fileName) {
        Path assetsDir = getAssetsDir(title);
        Path asset = assetsDir.resolve(requireSimpleName(fileName, "fileName")).normalize();
        if (! asset.startsWith(assetsDir)) {
            throw new IllegalArgumentException("非法的资源路径: " + fileName);
        }
        return asset;
    }

    public List<String> listAssets(String title) {
        Path dir = getAssetsDir(title);
        if (! Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取图片资源列表失败: " + dir, e);
        }
    }

    // ============================ 内部工具 ============================

    private Path novelDir(String title) {
        Path dir = basePath.resolve(requireSimpleName(title, "title")).normalize();
        if (! dir.startsWith(basePath) || dir.equals(basePath)) {
            throw new IllegalArgumentException("非法的小说目录: " + title);
        }
        return dir;
    }

    private Path chapterDir(String title, String chapterId) {
        return novelDir(title).resolve(requireSimpleName(chapterId, "chapterId"));
    }

    private Path savePath(String title, String saveName) {
        return novelDir(title).resolve(SAVE_DIR).resolve(normalizeSaveName(saveName) + JSON_SUFFIX);
    }

    /** 兼容误传的 .json 后缀，避免拼出 save1.json.json */
    private String normalizeSaveName(String saveName) {
        String name = requireSimpleName(saveName, "saveName");
        if (name.toLowerCase().endsWith(JSON_SUFFIX)) {
            name = name.substring(0, name.length() - JSON_SUFFIX.length());
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("非法的存档名: " + saveName);
        }
        return name;
    }

    /** 单层目录 / 文件名校验，杜绝 .. 等路径穿越 */
    private String requireSimpleName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (name.contains("/") || name.contains("\\") || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("非法的 " + field + ": " + name);
        }
        return name;
    }

    /** 解析 "{order}-{chapterTitle}" 目录名，不合规返回 null */
    private VisualNovelChapter parseChapter(String chapterId) {
        int split = chapterId.indexOf('-');
        if (split <= 0 || split == chapterId.length() - 1) {
            return null;
        }
        try {
            return new VisualNovelChapter()
                    .setOrder(Integer.parseInt(chapterId.substring(0, split)))
                    .setTitle(chapterId.substring(split + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String readChapterContent(Path chapterDir) {
        String content = readText(chapterDir.resolve(CHAPTER_MD));
        return content == null ? EMPTY_TEXT : content;
    }

    /** 读取文本文件；不存在、0 字节或纯空白统一返回 null */
    private String readText(Path path) {
        if (! Files.isRegularFile(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.isBlank() ? null : text;
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + path, e);
        }
    }

    private void writeText(Path path, String text) {
        ensureDir(path.getParent());
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败: " + path, e);
        }
    }

    /** 格式化写入 JSON；空 Map 统一落成 {}，保持文件整洁 */
    private void writeJson(Path path, Object value) {
        String json;
        try {
            json = value instanceof Map<?, ?> map && map.isEmpty() ? EMPTY_JSON_OBJECT : objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化 JSON 失败: " + path, e);
        }
        writeText(path, json);
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("解析 JSON 失败", e);
        }
    }

    private <T> T readJson(String json, JavaType type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("解析 JSON 失败", e);
        }
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建目录失败: " + dir, e);
        }
    }

    private boolean isEmptyFile(Path path) {
        try {
            return Files.size(path) == 0;
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件大小失败: " + path, e);
        }
    }

    /** 递归删除目录；目录不存在返回 false */
    private boolean deleteRecursively(Path dir) {
        if (! Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除目录失败: " + dir, e);
        }
        return true;
    }
}
