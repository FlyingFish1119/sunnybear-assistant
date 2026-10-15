package com.fishsunny.assistant.utils;

/*
 * @Usage 项目文件管理器 —— {basePath}/project/{projectId} 目录的沙箱读写。
 *        项目文件是跟着「项目」走的一揽子资料（文档、素材、产出物……），
 *        目录只按 projectId 区分，与会话无关：同一个项目下的所有会话天然共享这一份。
 *
 *        与 CoreFileManager 同构：路径解析复用 SessionFileManager.resolveUnder，
 *        条目结构复用 SessionFileEntry（前端「会话文件 / 核心文件 / 项目文件」可共用一套渲染），
 *        上传重名同样自动加序号。
 *
 *        比 SessionFileManager 简单：不做「文件引用展开给模型」「图片压缩落盘」这类会话专属的事，
 *        只保留最基础的一层目录读写 + 上传 / 改名 / 删除。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.config.AssistantPathConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class ProjectFileManager {

    private static final String PROJECT_DIR = "project";

    /** 文本读取上限 2MB：与核心 / 会话文件同口径，编辑器吃不下超大文件 */
    private static final long MAX_TEXT_READ_BYTES = 2 * 1024 * 1024;

    private final String basePath;

    public ProjectFileManager(AssistantPathConfig assistantPathConfig) {
        if (assistantPathConfig == null || !StringUtils.hasText(assistantPathConfig.getFileBasePath())) {
            this.basePath = System.getProperty("user.dir");
        } else {
            this.basePath = assistantPathConfig.getFileBasePath();
        }
    }

    /* ======================== 路径 ======================== */

    /** 项目文件根目录：{basePath}/project/{projectId} */
    public Path buildProjectDirPath(String projectId) {
        requireProjectId(projectId);
        return Path.of(basePath, PROJECT_DIR, projectId);
    }

    /**
     * 建出项目文件根目录（幂等）。
     * <p>新建项目时想立刻把目录摆在那儿可以调一下；不调也无所谓 ——
     * 写文件 / 上传时父目录会自动创建，列目录时目录不存在则返回空列表。
     */
    public Path ensureProjectDir(String projectId) throws IOException {
        Path dir = buildProjectDirPath(projectId);
        Files.createDirectories(dir);
        return dir;
    }

    /** 项目沙箱内解析：结果必落在 {basePath}/project/{projectId} 之下（禁绝对路径 / 盘符 / .. 回溯） */
    public Path resolveProjectFilePath(String projectId, String relativePath) {
        return SessionFileManager.resolveUnder(buildProjectDirPath(projectId), relativePath);
    }

    /* ======================== 文件管理 ======================== */

    /**
     * 列出项目目录下某一层的条目（不递归）：目录在前，同类型按名称排序。
     * <p>目录不存在时返回空列表 —— 第一次打开项目文件时不该看到报错。
     *
     * @param relativeDir 相对项目根目录的子目录，空串表示根
     */
    public List<SessionFileManager.SessionFileEntry> listProjectFiles(String projectId, String relativeDir) throws IOException {
        Path dir = resolveProjectFilePath(projectId, relativeDir);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<SessionFileManager.SessionFileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                    boolean isDir = attrs.isDirectory();
                    entries.add(new SessionFileManager.SessionFileEntry(
                            child.getFileName().toString(),
                            relativePathOf(projectId, child),
                            isDir,
                            isDir ? -1L : attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                    ));
                } catch (IOException e) {
                    log.warn("读取文件属性失败: {}", child, e);
                }
            }
        }
        entries.sort(Comparator
                .comparing(SessionFileManager.SessionFileEntry::directory, Comparator.reverseOrder())
                .thenComparing(SessionFileManager.SessionFileEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * 读取文本内容（UTF-8）。超过 2MB 直接抛异常，由上层转成提示。
     *
     * @return 文件不存在时返回 null
     */
    public String readProjectText(String projectId, String relativePath) throws IOException {
        Path filePath = resolveProjectFilePath(projectId, relativePath);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        long size = Files.size(filePath);
        if (size > MAX_TEXT_READ_BYTES) {
            throw new IllegalStateException("文件大小 " + (size / 1024) + "KB 超过编辑器上限 "
                    + (MAX_TEXT_READ_BYTES / 1024) + "KB，请下载后查看");
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /** 读取文件原始字节（图片预览、下载用）。文件不存在返回 null */
    public byte[] readProjectBytes(String projectId, String relativePath) throws IOException {
        Path filePath = resolveProjectFilePath(projectId, relativePath);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        return Files.readAllBytes(filePath);
    }

    /**
     * 目标是不是一个已存在的普通文件。
     * <p>删除入口用它挡住「误删目录」：面板只编辑文件，目录不在删除范围内。
     */
    public boolean isProjectFile(String projectId, String relativePath) {
        return Files.isRegularFile(resolveProjectFilePath(projectId, relativePath));
    }

    /**
     * 项目文件的绝对路径（只认项目根下的文件），供「把文件挂进消息」的场景直接引用。
     * <p>存磁盘路径而不是「{sessionId}:文件名」那种可移植引用：项目文件是跟着开发不断变化的活文件，
     * 挂它要的是每轮读最新，而不是会话开局那一刻的快照。
     *
     * @return 绝对路径；projectId 为空、文件名不合法或文件不存在时返回 null
     */
    public Path resolveExistingProjectFile(String projectId, String fileName) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(fileName)) {
            return null;
        }
        try {
            Path file = resolveProjectFilePath(projectId, fileName).toAbsolutePath().normalize();
            return Files.isRegularFile(file) ? file : null;
        } catch (Exception e) {
            log.warn("解析项目文件路径失败 projectId={}, file={}: {}", projectId, fileName, e.getMessage());
            return null;
        }
    }

    /** 写回文本（整文件覆盖），父目录不存在时自动创建 */
    public void writeProjectText(String projectId, String relativePath, String content) throws IOException {
        requirePlainFileName(relativePath);
        Path filePath = resolveProjectFilePath(projectId, relativePath);
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("目标是一个目录，不能写入: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /** 新建文件。目标已存在时抛异常，避免把已有内容覆盖掉 */
    public void createProjectText(String projectId, String relativePath, String content) throws IOException {
        requirePlainFileName(relativePath);
        Path filePath = resolveProjectFilePath(projectId, relativePath);
        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("同名文件已存在: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /** 改名 / 移动（同目录或跨子目录都走这里）。源不存在、目标已存在都会抛异常 */
    public void renameProjectFile(String projectId, String fromPath, String toPath) throws IOException {
        Path source = resolveProjectFilePath(projectId, fromPath);
        Path target = resolveProjectFilePath(projectId, toPath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("源文件不存在: " + fromPath);
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标已存在: " + toPath);
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target);
    }

    /**
     * 删除文件或目录（目录递归删除，连同其下所有内容）。
     * <p>根目录本身受保护，不允许删除。
     */
    public void deleteProjectFile(String projectId, String relativePath) throws IOException {
        Path rootDir = buildProjectDirPath(projectId).toAbsolutePath().normalize();
        Path path = resolveProjectFilePath(projectId, relativePath);
        if (path.equals(rootDir)) {
            throw new IllegalArgumentException("不能删除项目文件根目录");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        deleteRecursively(path);
    }

    /**
     * 保存上传文件到指定项目子目录。同名自动加序号（img.png → img_1.png），
     * 返回落盘后的相对路径（供前端刷新树定位）。
     *
     * @param relativeDir 目标子目录，空串表示根
     */
    public String saveProjectUpload(String projectId, String relativeDir, String originalFilename, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        Path dir = resolveProjectFilePath(projectId, relativeDir);
        String safeName = SessionFileManager.sanitizeFileName(originalFilename);
        Path target = SessionFileManager.uniquePath(dir, safeName);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return relativePathOf(projectId, target);
    }

    /**
     * 删除项目的整个文件目录（{basePath}/project/{projectId}）。
     * <p>目录不存在视为已完成；是否要随「删除项目」一起调用，由上层决定 ——
     * 项目解绑不删资料、删除项目才清目录，这一刀切在 service 层更合适。
     */
    public void deleteProjectDir(String projectId) {
        Path rootPath = buildProjectDirPath(projectId);
        if (!Files.exists(rootPath)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(rootPath)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("删除项目文件失败: {}", path, e);
                }
            }
            log.info("已删除项目文件目录: {}", rootPath);
        } catch (IOException e) {
            log.warn("删除项目文件目录失败 [{}]: {}", projectId, e.getMessage());
        }
    }

    /* ======================== 辅助 ======================== */

    /** 递归删除：目录先删内容再删自身（Files.walk 逆序保证子项先于父项） */
    private void deleteRecursively(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.delete(path);
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** 文件相对项目根目录的路径（统一 / 分隔，供前端做树形 key） */
    private String relativePathOf(String projectId, Path child) {
        return buildProjectDirPath(projectId).toAbsolutePath().normalize()
                .relativize(child.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /**
     * 项目文件名校验：项目资料不做目录层级，文件名里不许出现路径分隔符或回溯。
     * <p>挡在写入这一头，项目目录里就长不出子目录来（自动追加清单只认文件名，也是靠这个前提）。
     */
    private void requirePlainFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("文件名不能带路径分隔符或 ..（项目文件只放根目录下）: " + fileName);
        }
    }

    /**
     * 项目 ID 校验：它会被直接拼成目录名，不能夹带路径分隔符、盘符或回溯。
     * <p>放行普通 id（如 uuid）；含 {@code / \ : ..} 一律拒绝，避免拼出目录之外的路径。
     */
    private void requireProjectId(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new IllegalArgumentException("Project ID cannot be empty");
        }
        if (projectId.contains("/") || projectId.contains("\\")
                || projectId.contains(":") || projectId.contains("..")) {
            throw new IllegalArgumentException("项目 ID 不能包含路径分隔符: " + projectId);
        }
    }
}
