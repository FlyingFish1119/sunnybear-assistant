package com.fishsunny.assistant.engine.tool.extension;

/*
 * @Usage 无头浏览器服务 - 使用 Playwright 渲染 JS 动态页面，终极反爬对抗 + 浏览器自动化交互
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9 17:33
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Playwright 无头浏览器服务，提供 JS 渲染能力 + 浏览器自动化交互。
 * <p>
 * 两种使用模式：
 * <ul>
 *   <li><b>fetch 模式</b>（WebReaderTool 使用）：每次创建独立 BrowserContext，用完即关，无状态</li>
 *   <li><b>交互模式</b>（Browser 工具使用）：按 sessionId 维护独立 BrowserContext + Page，
 *       不同会话完全隔离，浏览器启动后常驻不关</li>
 * </ul>
 */
@Service
public class PlaywrightBrowserService {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserService.class);

    private static final List<String> LAUNCH_ARGS = List.of(
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--disable-blink-features=AutomationControlled"
    );

    private static final String STEALTH_INIT_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
            "delete navigator.__proto__.webdriver;" +
            "Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});" +
            "Object.defineProperty(navigator, 'languages', {get: () => ['zh-CN', 'zh', 'en']});";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15"
    );

    /** 会话空闲超时（分钟），默认 30 */
    private static final int SESSION_IDLE_MINUTES = 30;
    /** 最大会话数 */
    private static final int MAX_SESSIONS = 50;

    /** 单个会话的浏览器状态 */
    private record SessionState(BrowserContext context, Page page) {}

    // ==================== 实例字段 ====================

    private final Object lock = new Object();
    private volatile Playwright playwright;
    private volatile Browser browser;
    private final AtomicLong lastUsedAt = new AtomicLong(-1);

    /** 按 sessionId 隔离的交互模式会话，Caffeine 缓存自动处理过期和淘汰 */
    private final Cache<String, SessionState> sessions = Caffeine.newBuilder()
            .maximumSize(MAX_SESSIONS)
            .expireAfterAccess(SESSION_IDLE_MINUTES, TimeUnit.MINUTES)
            .removalListener((String key, SessionState value, RemovalCause cause) -> {
                if (value != null) {
                    log.info("Playwright: [{}] 会话已淘汰（{}）", key, cause);
                    try { value.page().close(); } catch (Exception ignored) {}
                    try { value.context().close(); } catch (Exception ignored) {}
                }
            })
            .build();

    // ==================== 结果类型 ====================

    public record FetchResult(String title, String htmlContent) {}

    // ==================== fetch 模式（WebReaderTool 使用，每次独立 Context） ====================

    public FetchResult fetch(String url, int timeoutMs) throws ToolExecutor.ToolExecuteException {
        ensureBrowserReady();
        lastUsedAt.set(System.currentTimeMillis());

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(randomUserAgent())
                .setViewportSize(1920, 1080)
                .setLocale("zh-CN"))) {

            context.addInitScript(STEALTH_INIT_SCRIPT);

            try (Page page = context.newPage()) {
                page.navigate(url);
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                            .setTimeout(timeoutMs));
                } catch (TimeoutError e) {
                    log.warn("Playwright: 页面加载超时（{}ms），使用已加载的部分内容: {}", timeoutMs, url);
                }

                String title = page.title();
                String htmlContent = page.content();
                return new FetchResult(title, htmlContent);
            }

        } catch (Exception e) {
            log.error("Playwright: 浏览器操作异常，标记浏览器为失效: {}", e.getMessage());
            invalidateBrowser();
            throw new ToolExecutor.ToolExecuteException(
                    "无头浏览器抓取失败: " + e.getMessage() + "。URL: " + url);
        }
    }

    // ==================== 交互模式（按 sessionId 隔离） ====================

    public String navigate(String sessionId, String url, int timeoutMs) throws ToolExecutor.ToolExecuteException {
        Page page = getOrCreatePage(sessionId);
        try {
            page.navigate(url);
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                        .setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                log.warn("Playwright: [{}] 页面加载超时（{}ms），使用已加载的部分内容: {}", sessionId, timeoutMs, url);
            }
            lastUsedAt.set(System.currentTimeMillis());
            return page.title();
        } catch (Exception e) {
            log.error("Playwright: [{}] 导航失败: {}", sessionId, e.getMessage());
            throw new ToolExecutor.ToolExecuteException("浏览器导航失败: " + e.getMessage() + "。URL: " + url);
        }
    }

    public void click(String sessionId, String selector) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.locator(selector).first().click();
            lastUsedAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("点击元素失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public void type(String sessionId, String selector, String text) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.locator(selector).first().fill(text);
            lastUsedAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("输入文本失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public void scroll(String sessionId, int deltaY) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.evaluate("window.scrollBy(0, " + deltaY + ")");
            lastUsedAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("滚动页面失败: " + e.getMessage());
        }
    }

    public void scroll(String sessionId, String selector, int deltaY) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.locator(selector).first().evaluate("el => el.scrollBy(0, " + deltaY + ")");
            lastUsedAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "滚动元素 [" + selector + "] 失败: " + e.getMessage());
        }
    }

    public void drag(String sessionId, String sourceSelector, String targetSelector) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.locator(sourceSelector).first().dragTo(page.locator(targetSelector).first());
            lastUsedAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "拖拽失败 [" + sourceSelector + " → " + targetSelector + "]: " + e.getMessage());
        }
    }

    public String screenshot(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            byte[] screenshotBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            lastUsedAt.set(System.currentTimeMillis());
            return ScaleImageHelper.byteArrayToBase64(screenshotBytes);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("截图失败: " + e.getMessage());
        }
    }

    public String screenshotToFile(String sessionId, String outputPath) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            Path path = Path.of(outputPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(false));
            lastUsedAt.set(System.currentTimeMillis());
            return path.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("截图保存失败: " + e.getMessage());
        }
    }

    public String getContent(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            lastUsedAt.set(System.currentTimeMillis());
            return (String) page.evaluate(
                    "(() => {" +
                    "  const c = document.documentElement.cloneNode(true);" +
                    "  c.querySelectorAll('script, style, noscript, link[rel*=\"stylesheet\"], svg').forEach(e => e.remove());" +
                    "  return c.outerHTML;" +
                    "})()"
            );
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取页面内容失败: " + e.getMessage());
        }
    }

    public String getTitle(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            lastUsedAt.set(System.currentTimeMillis());
            return page.title();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取页面标题失败: " + e.getMessage());
        }
    }

    public String getCurrentUrl(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            lastUsedAt.set(System.currentTimeMillis());
            return page.url();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取当前 URL 失败: " + e.getMessage());
        }
    }

    public void waitForSelector(String sessionId, String selector, int timeoutMs) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            lastUsedAt.set(System.currentTimeMillis());
        } catch (TimeoutError e) {
            throw new ToolExecutor.ToolExecuteException(
                    "等待元素超时（" + timeoutMs + "ms）[" + selector + "]: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("等待元素失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public String evaluate(String sessionId, String js) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingPage(sessionId);
        try {
            Object result = page.evaluate(js);
            lastUsedAt.set(System.currentTimeMillis());
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("执行 JavaScript 失败: " + e.getMessage());
        }
    }

    // ==================== 内部：Session 级别的 Page 管理（Caffeine 缓存自动过期/淘汰） ====================

    private Page getOrCreatePage(String sessionId) throws ToolExecutor.ToolExecuteException {
        ensureBrowserReady();
        SessionState state = sessions.get(sessionId, k -> {
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(randomUserAgent())
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN"));
            context.addInitScript(STEALTH_INIT_SCRIPT);
            Page page = context.newPage();
            log.info("Playwright: [{}] 会话已创建（缓存 {} 个）", sessionId, sessions.estimatedSize());
            return new SessionState(context, page);
        });
        return state.page();
    }

    private Page getExistingPage(String sessionId) throws ToolExecutor.ToolExecuteException {
        SessionState state = sessions.getIfPresent(sessionId);
        if (state != null) {
            return state.page();
        }
        log.info("Playwright: [{}] 会话尚未创建，自动初始化...", sessionId);
        return getOrCreatePage(sessionId);
    }

    // ==================== 浏览器生命周期管理 ====================

    private void ensureBrowserReady() throws ToolExecutor.ToolExecuteException {
        if (browser != null) {
            return;
        }

        synchronized (lock) {
            if (browser != null) {
                return;
            }

            try {
                playwright = Playwright.create();

                try {
                    browser = playwright.chromium().launch(
                            new BrowserType.LaunchOptions()
                                    .setHeadless(true)
                                    .setArgs(LAUNCH_ARGS));
                } catch (Exception e) {
                    log.warn("Playwright: Chromium 未安装或启动失败，尝试自动安装... 原因: {}", e.getMessage());
                    installChromium();
                    browser = playwright.chromium().launch(
                            new BrowserType.LaunchOptions()
                                    .setHeadless(true)
                                    .setArgs(LAUNCH_ARGS));
                }

                log.info("Playwright: 无头浏览器已就绪 (Chromium)");
                lastUsedAt.set(System.currentTimeMillis());
            } catch (Exception e) {
                closeQuietly();
                throw new ToolExecutor.ToolExecuteException(
                        "无法启动无头浏览器。请确保系统已安装 Chromium，"
                        + "或手动运行: mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                        + "-Dexec.args='install chromium'。原因: " + e.getMessage());
            }
        }
    }

    private void installChromium() {
        log.info("Playwright: 开始安装 Chromium，请耐心等待...");
        try {
            com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"});
            log.info("Playwright: Chromium 安装完成");
        } catch (Exception e) {
            log.error("Playwright: Chromium 自动安装失败", e);
            throw new RuntimeException("Chromium 安装失败: " + e.getMessage(), e);
        }
    }

    private static String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    private void invalidateBrowser() {
        closeAllSessions();
        Browser b = browser;
        browser = null;
        Playwright p = playwright;
        playwright = null;
        if (b != null) { try { b.close(); } catch (Exception ignored) {} }
        if (p != null) { try { p.close(); } catch (Exception ignored) {} }
    }

    private void closeAllSessions() {
        sessions.asMap().forEach((id, state) -> {
            try { state.page().close(); } catch (Exception ignored) {}
            try { state.context().close(); } catch (Exception ignored) {}
        });
        sessions.invalidateAll();
    }

    private void closeQuietly() {
        closeAllSessions();
        Browser b = browser;
        browser = null;
        Playwright p = playwright;
        playwright = null;
        lastUsedAt.set(-1);
        if (b != null) {
            try { b.close(); } catch (Exception e) {
                log.warn("Playwright: 关闭 Browser 时发生异常: {}", e.getMessage());
            }
        }
        if (p != null) {
            try { p.close(); } catch (Exception e) {
                log.warn("Playwright: 关闭 Playwright 时发生异常: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Playwright: 正在关闭无头浏览器（{} 个会话）...", sessions.estimatedSize());
        closeQuietly();
        log.info("Playwright: 已关闭");
    }
}
