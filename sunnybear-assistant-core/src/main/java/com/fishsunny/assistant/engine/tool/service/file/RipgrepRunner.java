package com.fishsunny.assistant.engine.tool.service.file;

/*
 * @Usage 基于 ripgrep 的文件内容搜索执行器，作为 FileSearchTool 的高性能后端
 *
 * 职责：
 *  1. 平台探测 + 从 classpath 资源（native/<platform>/rg[.exe]）解压内置 rg 二进制
 *  2. 通过 ProcessBuilder 以 --json 方式调用 rg，解析结构化输出
 *  3. rg 不可用 / 执行失败时抛出异常，由上层返回错误
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26 14:40
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ripgrep 后端执行器。
 * <p>
 * 内置二进制按 {@code native/<os>-<arch>/rg[.exe]} 存放在 classpath 资源中，运行时解压到缓存目录后执行，
 * 避免部署机器需要单独安装 rg。
 */
@Slf4j
public class RipgrepRunner {

    /** classpath 资源前缀 */
    private static final String RESOURCE_PREFIX = "native/";

    /** rg 进程最大执行时间（毫秒），需小于工具层 30s 硬超时，留出回退余量 */
    private static final long RG_TIMEOUT_MS = 25_000;

    /** 跳过超过该大小的文件 */
    private static final String DEFAULT_MAX_FILESIZE = "50M";

    /** 匹配行超过该字节数后截断，避免超长单行撑爆 LLM 上下文 */
    private static final int MAX_COLUMNS = 500;

    private final ObjectMapper objectMapper;
    private final Path cacheRoot;

    /** 已解析出的 rg 二进制路径（首次解析后缓存） */
    private volatile Path cachedBinary;

    public RipgrepRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cacheRoot = Paths.get(System.getProperty("java.io.tmpdir"), "sunnybear-rg");
    }

    // ======================== 数据结构 ========================

    /** 一次搜索的入参 */
    public static class SearchRequest {
        public Path root;
        public String pattern;
        public boolean useRegex;
        public String filter;
        public int depth;
        public boolean caseSensitive;
        public int contextLines;
        public int maxResults;
        /** 是否搜索隐藏文件（dotfile），默认 false（rg 默认跳过） */
        public boolean hidden;
        /** 排除的目录名/glob（如 node_modules、target），会拼成排除型 glob 传给 rg */
        public List<String> excludes = new ArrayList<>();
    }

    public static class MatchLine {
        public int lineNumber;
        public String content;
        public boolean match;
    }

    public static class FileResult {
        public String relativePath;
        public List<MatchLine> lines = new ArrayList<>();
    }

    public static class SearchResult {
        public List<FileResult> files = new ArrayList<>();
        public int totalMatches;
        public boolean truncated;
    }

    /** rg 二进制不可用（内置资源缺失 / 平台不受支持 / 解压失败） */
    public static class RgUnavailableException extends Exception {
        public RgUnavailableException(String message) {
            super(message);
        }
    }

    /** rg 执行错误（退出码 2，通常是参数/正则/glob 非法） */
    public static class RgExecutionException extends Exception {
        public RgExecutionException(String message) {
            super(message);
        }
    }

    /** rg 超时 */
    public static class RgTimeoutException extends Exception {
        public RgTimeoutException(String message) {
            super(message);
        }
    }

    // ======================== 对外入口 ========================

    /**
     * 执行一次文件内容搜索。
     *
     * @throws RgUnavailableException rg 不可用
     * @throws RgExecutionException   rg 执行失败（参数非法等）
     * @throws RgTimeoutException     rg 超时
     */
    public SearchResult search(SearchRequest req) throws RgUnavailableException, RgExecutionException, RgTimeoutException, IOException {
        Path binary = resolveBinary();
        if (binary == null) {
            throw new RgUnavailableException("未找到可用的 rg 二进制（内置资源缺失或当前平台不受支持）");
        }

        ProcessBuilder pb = new ProcessBuilder(buildArgs(binary, req));
        pb.directory(req.root.toFile());
        Process process = pb.start();

        SearchResult result = new SearchResult();
        Thread stdoutReader = new Thread(() -> parseStdout(process, req, result), "rg-stdout-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        boolean finished;
        try {
            finished = process.waitFor(RG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RgTimeoutException("rg 搜索被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new RgTimeoutException("rg 搜索超时（超过 " + RG_TIMEOUT_MS + "ms），目录可能过大或包含过多文件");
        }
        try {
            stdoutReader.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        if (exitCode == 2) {
            throw new RgExecutionException("rg 执行失败（exit=2），请检查正则/glob 参数是否合法");
        }
        return result;
    }

    // ======================== 二进制解析与解压 ========================

    /** 判断当前平台是否有可用的 rg 二进制 */
    public boolean isAvailable() {
        return resolveBinary() != null;
    }

    /**
     * 解析 rg 二进制路径：外部配置优先，其次从 classpath 资源解压。
     */
    private Path resolveBinary() {
        if (cachedBinary != null && Files.exists(cachedBinary)) {
            return cachedBinary;
        }
        String platform = detectPlatform();
        if (platform == null) {
            return null;
        }
        String binaryName = platform.startsWith("windows") ? "rg.exe" : "rg";
        String resource = RESOURCE_PREFIX + platform + "/" + binaryName;
        try {
            URL url = getClass().getClassLoader().getResource(resource);
            if (url == null) {
                log.warn("未找到内置 rg 资源: {}", resource);
                return null;
            }
            Path extracted = extractIfNeeded(platform, url);
            cachedBinary = extracted;
            return extracted;
        } catch (IOException e) {
            log.warn("rg 资源解压失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 探测当前操作系统 + 架构，映射到内置资源目录名，如 windows-x86_64、linux-aarch64。
     */
    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osKey;
        if (os.contains("win")) {
            osKey = "windows";
        } else if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
        } else {
            return null;
        }

        String archKey;
        switch (arch) {
            case "amd64", "x86_64", "x64" -> archKey = "x86_64";
            case "aarch64", "arm64" -> archKey = "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> archKey = "x86";
            case "arm", "armv7", "armv7l", "armhf" -> archKey = "armv7";
            default -> {
                log.warn("不支持的操作系统架构: os={}, arch={}", os, arch);
                return null;
            }
        }
        return osKey + "-" + archKey;
    }

    /**
     * 从 classpath 解压 rg 到缓存目录，已解压且文件大小一致时直接复用。
     */
    private Path extractIfNeeded(String platform, URL url) throws IOException {
        Path dir = cacheRoot.resolve(platform);
        Files.createDirectories(dir);
        String binaryName = platform.startsWith("windows") ? "rg.exe" : "rg";
        Path target = dir.resolve(binaryName);
        Path sizeFile = dir.resolve(".size");

        long currentSize = Files.exists(target) ? Files.size(target) : -1;
        if (currentSize > 0 && currentSize == readStoredSize(sizeFile)) {
            return target;
        }

        Path tmp = Files.createTempFile(dir, binaryName, ".tmp");
        try (InputStream in = url.openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(sizeFile, String.valueOf(Files.size(target)));
        if (!platform.startsWith("windows")) {
            target.toFile().setExecutable(true, false);
        }
        log.info("已解压内置 rg 到 {}", target);
        return target;
    }

    private long readStoredSize(Path sizeFile) {
        try {
            return Long.parseLong(Files.readString(sizeFile).trim());
        } catch (Exception e) {
            // 标记文件缺失/损坏时返回 -1，让上层重新解压，避免返回 null 触发拆箱 NPE
            return -1;
        }
    }

    // ======================== 命令构造 ========================

    private List<String> buildArgs(Path binary, SearchRequest req) {
        List<String> args = new ArrayList<>();
        args.add(binary.toString());
        args.add("--json");
        args.add("--no-config");            // 忽略用户 ~/.ripgreprc，行为可预期
        args.add("--color");
        args.add("never");
        args.add("--max-count");
        args.add(String.valueOf(req.maxResults)); // 单文件匹配上限
        args.add("--max-depth");
        args.add(String.valueOf(req.depth));
        args.add("--max-filesize");
        args.add(DEFAULT_MAX_FILESIZE);
        args.add("--max-columns");
        args.add(String.valueOf(MAX_COLUMNS));
        args.add("--max-columns-preview");
        if (!req.caseSensitive) {
            args.add("-i");
        }
        if (req.useRegex) {
            // 使用 PCRE2，兼容 Java 风格的环视/反向引用（内置 rg 均带 +pcre2）
            args.add("-P");
        } else {
            // 默认把 pattern 当普通文本匹配，等价于 Pattern.quote
            args.add("-F");
        }
        if (req.hidden) {
            args.add("--hidden");
        }
        if (req.contextLines > 0) {
            args.add("-C");
            args.add(String.valueOf(req.contextLines));
        }
        if (StringUtils.hasText(req.filter)) {
            args.add("-g");
            args.add(req.filter);
        }
        // 常见构建产物/依赖目录排除，避免非 git 仓库目录下的超时
        for (String exclude : req.excludes) {
            if (StringUtils.hasText(exclude)) {
                args.add("-g");
                args.add("!**/" + exclude.trim());
            }
        }
        // pattern 作为独立 argv 元素传入，无 shell 注入风险
        args.add(req.pattern);
        // 工作目录设为搜索根，输出路径即相对路径
        args.add(".");
        return args;
    }

    // ======================== 输出解析 ========================

    /**
     * 逐行解析 rg --json 输出，按 begin/match/context 事件还原为按文件分组的结果。
     * 达到全局 maxResults 时主动销毁进程截断，避免无界输出。
     */
    private void parseStdout(Process process, SearchRequest req, SearchResult result) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            FileResult current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.totalMatches >= req.maxResults) {
                    result.truncated = true;
                    process.destroy();
                    break;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (Exception e) {
                    log.warn("忽略无法解析的 rg 输出行: {}", e.getMessage());
                    continue;
                }
                String type = node.path("type").asText();
                JsonNode data = node.path("data");
                switch (type) {
                    case "begin" -> {
                        current = new FileResult();
                        current.relativePath = stripLeadingDot(data.path("path").path("text").asText(""));
                        result.files.add(current);
                    }
                    case "match" -> {
                        if (current == null) {
                            continue;
                        }
                        MatchLine matchLine = new MatchLine();
                        matchLine.lineNumber = data.path("line_number").asInt(0);
                        matchLine.content = stripLineEnd(data.path("lines").path("text").asText(""));
                        matchLine.match = true;
                        current.lines.add(matchLine);
                        result.totalMatches++;
                    }
                    case "context" -> {
                        if (current == null) {
                            continue;
                        }
                        MatchLine contextLine = new MatchLine();
                        contextLine.lineNumber = data.path("line_number").asInt(0);
                        contextLine.content = stripLineEnd(data.path("lines").path("text").asText(""));
                        contextLine.match = false;
                        current.lines.add(contextLine);
                    }
                    default -> {
                        // begin/end/summary 事件无匹配内容，忽略
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取 rg 输出失败: {}", e.getMessage());
        }
    }

    /** 去掉 rg 以当前目录为搜索根时路径前的 .\ 或 ./ 前缀 */
    private static String stripLeadingDot(String s) {
        if (s.startsWith("./") || s.startsWith(".\\")) {
            return s.substring(2);
        }
        return s;
    }

    /** 去掉行尾的 \r\n（rg --json 在 Windows 下 lines.text 带 CRLF） */
    private static String stripLineEnd(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
