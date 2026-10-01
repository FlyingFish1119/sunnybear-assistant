package com.fishsunny.assistant.utils;

/*
 * @Usage 核心文件管理器 —— {basePath}/core 目录的沙箱读写。
 *        核心文件是用户上传、长期保存、随时引用的文件，跨会话存在。
 *
 *        与 SessionFileManager 同构：路径解析复用其静态 resolveUnder，条目结构复用
 *        SessionFileEntry，前端「核心文件 / 会话文件」两个模式共用一套渲染逻辑。
 *        区别只在根目录（core/ 而非 session/{id}/file/）和「同名自动加序号」的落盘策略
 *        （上传 / 转存是用户随手操作，撞名不该报错打断）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/22
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
public class CoreFileManager {

    private static final String CORE_DIR = "core";

    /** 文本读取上限 2MB：与会话文件同口径，编辑器吃不下超大文件 */
    private static final long MAX_TEXT_READ_BYTES = 2 * 1024 * 1024;

    private final String basePath;
    private final SessionFileManager sessionFileManager;

    public CoreFileManager(AssistantPathConfig assistantPathConfig, SessionFileManager sessionFileManager) {
        if (assistantPathConfig == null || !StringUtils.hasText(assistantPathConfig.getFileBasePath())) {
            this.basePath = System.getProperty("user.dir");
        } else {
            this.basePath = assistantPathConfig.getFileBasePath();
        }
        this.sessionFileManager = sessionFileManager;
    }

    /** 核心文件根目录：{basePath}/core */
    public Path buildCoreDirPath() {
        return Path.of(basePath, CORE_DIR);
    }

    /** 核心沙箱内解析：结果必落在 {basePath}/core 之下（禁止绝对路径 / 盘符 / .. 回溯） */
    public Path resolveCoreFilePath(String relativePath) {
        return SessionFileManager.resolveUnder(buildCoreDirPath(), relativePath);
    }

    /* ======================== 核心文件管理 ======================== */

    /**
     * 列出核心文件目录下某一层的条目（不递归）：目录在前，同类型按名称排序。
     * <p>目录不存在时返回空列表 —— 核心库第一次打开时不该看到报错。
     *
     * @param relativeDir 相对核心目录的子目录，空串表示根
     */
    public List<SessionFileManager.SessionFileEntry> listCoreFiles(String relativeDir) throws IOException {
        Path dir = resolveCoreFilePath(relativeDir);
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
                            relativePathOf(child),
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
    public String readCoreText(String relativePath) throws IOException {
        Path filePath = resolveCoreFilePath(relativePath);
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

    /** 读取文件原始字节（图片预览、下载、加载到发送栏用）。文件不存在返回 null */
    public byte[] readCoreBytes(String relativePath) throws IOException {
        Path filePath = resolveCoreFilePath(relativePath);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        return Files.readAllBytes(filePath);
    }

    /** 写回文本（整文件覆盖），父目录不存在时自动创建 */
    public void writeCoreText(String relativePath, String content) throws IOException {
        Path filePath = resolveCoreFilePath(relativePath);
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("目标是一个目录，不能写入: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /** 新建文件。目标已存在时抛异常，避免把已有内容覆盖掉 */
    public void createCoreText(String relativePath, String content) throws IOException {
        Path filePath = resolveCoreFilePath(relativePath);
        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("同名文件已存在: " + relativePath);
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /** 改名 / 移动。源不存在、目标已存在都会抛异常 */
    public void renameCoreFile(String fromPath, String toPath) throws IOException {
        Path source = resolveCoreFilePath(fromPath);
        Path target = resolveCoreFilePath(toPath);
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
     * <p>根目录本身受保护，不允许删除；前端在删除目录前会给出递归删除的二次确认。
     */
    public void deleteCoreFile(String relativePath) throws IOException {
        Path rootDir = buildCoreDirPath().toAbsolutePath().normalize();
        Path path = resolveCoreFilePath(relativePath);
        if (path.equals(rootDir)) {
            throw new IllegalArgumentException("不能删除核心文件根目录");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        deleteRecursively(path);
    }

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

    /* ======================== 上传 / 转存（同名自动加序号） ======================== */

    /**
     * 保存上传文件到指定核心子目录。同名自动加序号（img.png → img_1.png），
     * 返回落盘后的相对路径（供前端刷新树定位）。
     *
     * @param relativeDir 目标子目录，空串表示根
     */
    public String saveUpload(String relativeDir, String originalFilename, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        Path dir = resolveCoreFilePath(relativeDir);
        String safeName = SessionFileManager.sanitizeFileName(originalFilename);
        Path target = SessionFileManager.uniquePath(dir, safeName);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return relativePathOf(target);
    }

    /**
     * 会话文件 / 目录转存到核心库（「提升为核心」）：复制一份到核心根目录，
     * 原文件保留在会话里不动。同名自动加序号，返回落盘后的相对路径。
     * <p>目录会连同子目录、文件一起递归复制，在核心库里保留原目录结构。
     */
    public String promoteFromSession(String sessionId, String sessionPath) throws IOException {
        return promoteFromSession(sessionId, sessionPath, null);
    }

    /**
     * 会话文件 / 目录转存到核心库，并允许指定落盘名。
     * <p>targetName 为空时沿用源条目名；用于「整个会话存为核心」时取会话名做文件夹名。
     */
    public String promoteFromSession(String sessionId, String sessionPath, String targetName) throws IOException {
        Path source = sessionFileManager.resolveSessionFilePath(sessionId, sessionPath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("会话文件不存在: " + sessionPath);
        }
        String rawName = StringUtils.hasText(targetName)
                ? targetName
                : source.getFileName().toString();
        String safeName = SessionFileManager.sanitizeFileName(rawName);
        Path target = SessionFileManager.uniquePath(buildCoreDirPath(), safeName);
        if (Files.isDirectory(source)) {
            copyDirectoryInto(source, target);
            log.info("会话目录提升为核心: {} -> {}", sessionPath, relativePathOf(target));
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            log.info("会话文件提升为核心: {} -> {}", sessionPath, relativePathOf(target));
        }
        return relativePathOf(target);
    }

    /** 递归复制目录，在 targetDir 下重建 sourceDir 的完整结构（targetDir 本身也随之创建） */
    private void copyDirectoryInto(Path sourceDir, Path targetDir) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            for (Path path : walk.toList()) {
                Path dest = targetDir.resolve(sourceDir.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            }
        }
    }

    /**
     * 把核心文件复制一份进指定会话的文件目录（「加载到发送栏」发送时调用）。
     * <p>前端只提交 {@code core-file-link:相对路径} 这一条引用，内容本体压根不过网络：
     * 后端同机复制，落盘名沿用原文件名、同名自动加序号，返回可移植引用
     * {@code sessionId:文件名}，与上传落盘的引用形态一致——喂模型时按会话文件正常展开。
     * <p>源文件不存在时抛异常，由调用方决定是否跳过该附件（不阻塞整条消息发送）。
     *
     * @param sessionId   目标会话 ID
     * @param corePath    相对核心目录的文件路径
     * @return 落库用的可移植引用（形如 {@code sessionId:文件名}）
     */
    public String copyCoreFileToSession(String sessionId, String corePath) throws IOException {
        Path source = resolveCoreFilePath(corePath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("核心文件不存在: " + corePath);
        }
        String safeName = SessionFileManager.sanitizeFileName(source.getFileName().toString());
        Path target = SessionFileManager.uniquePath(sessionFileManager.buildSessionDirPath(sessionId), safeName);
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        String ref = sessionFileManager.buildRef(sessionId, target.getFileName().toString());
        log.info("核心文件复制进会话: {} -> {}", corePath, ref);
        return ref;
    }

    /* ======================== 辅助 ======================== */

    /** 文件相对核心目录的路径（统一 / 分隔，供前端做树形 key） */
    private String relativePathOf(Path child) {
        return buildCoreDirPath().toAbsolutePath().normalize()
                .relativize(child.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
