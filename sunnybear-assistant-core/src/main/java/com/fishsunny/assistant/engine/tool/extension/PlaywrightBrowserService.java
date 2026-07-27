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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Playwright 无头浏览器服务，提供 JS 渲染能力 + 浏览器自动化交互。
 * <p>
 * 两种使用模式：
 * <ul>
 *   <li><b>fetch 模式</b>（WebReaderTool 使用）：每次调用创建独立的 Playwright 实例，用完即毁，完全隔离</li>
 *   <li><b>交互模式</b>（Browser 工具使用）：按 sessionId 维护独立的 Playwright 实例 + 完整浏览器栈，
 *       不同会话完全隔离，通过 Caffeine 缓存自动管理过期和淘汰</li>
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

    /** Playwright 启动 / Chromium 启动的超时时间（秒），防止资源耗尽时永久阻塞 */
    private static final int BROWSER_START_TIMEOUT_SECONDS = 30;
    /** 会话空闲超时（分钟），默认 30 */
    private static final int SESSION_IDLE_MINUTES = 30;
    /** 最大会话数 */
    private static final int MAX_SESSIONS = 50;

    // ==================== 会话状态 ====================

    /**
     * 每个交互会话持有完整的独立 Playwright 实例栈，
     * 不同 sessionId 之间完全隔离，一个会话崩溃不会影响其他会话。
     */
    private record SessionState(Playwright playwright, Browser browser, BrowserContext context, Page page) {}

    /** 按 sessionId 隔离的交互模式会话，Caffeine 缓存自动处理过期和淘汰 */
    private final Cache<String, SessionState> sessions = Caffeine.newBuilder()
            .maximumSize(MAX_SESSIONS)
            .expireAfterAccess(SESSION_IDLE_MINUTES, TimeUnit.MINUTES)
            .removalListener((String key, SessionState state, RemovalCause cause) -> {
                if (state != null) {
                    log.info("Playwright: [{}] 会话已淘汰（{}）", key, cause);
                    closeSession(state);
                }
            })
            .build();

    /** Chromium 安装锁，防止多线程同时触发安装 */
    private final Object chromiumInstallLock = new Object();

    /** fetch 模式的硬超时执行器，Java 层面兜底防止 Playwright 内部超时失效 */
    private final ExecutorService fetchExecutor = Executors.newCachedThreadPool();

    // ==================== 结果类型 ====================

    public record FetchResult(String title, String htmlContent) {}

    // ==================== fetch 模式（每次独立 Playwright 实例，用完即毁） ====================

    /**
     * 使用独立的 Playwright 实例抓取网页，调用结束后完全销毁。
     * 与交互模式的 session 完全隔离，互不影响。
     * <p>
     * 包含 Java 层面硬超时兜底：即使 Playwright 内部超时失效卡死，
     * {@code Future.get(timeoutMs + 5000)} 也会强制中断，确保不会无限期阻塞。
     */
    public FetchResult fetch(String url, int timeoutMs, boolean waitNet) throws ToolExecutor.ToolExecuteException {
        Future<FetchResult> future = fetchExecutor.submit(() -> fetchInternal(url, timeoutMs, waitNet));
        try {
            return future.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Playwright: fetch 触发 Java 硬超时（{}ms），已强制中断。URL: {}", timeoutMs, url);
            throw new ToolExecutor.ToolExecuteException(
                    "无头浏览器抓取硬超时（" + timeoutMs + "ms），已强制中断。URL: " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ToolExecutor.ToolExecuteException("无头浏览器抓取被中断。URL: " + url);
        } catch (Exception e) {
            log.error("Playwright: fetch 操作异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException(
                    "无头浏览器抓取失败: " + e.getMessage() + "。URL: " + url);
        }
    }

    /**
     * fetch 的实际执行逻辑，运行在独立线程中，由 {@link #fetch} 的 Future 超时兜底。
     */
    private FetchResult fetchInternal(String url, int timeoutMs, boolean waitNet) throws ToolExecutor.ToolExecuteException {
        try (Playwright pw = Playwright.create()) {
            Browser browser = launchBrowser(pw);
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(randomUserAgent())
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN"));

            context.addInitScript(STEALTH_INIT_SCRIPT);
            Page page = context.newPage();
            try {
                page.navigate(url, new Page.NavigateOptions().setTimeout(timeoutMs));
                Page.WaitForLoadStateOptions options = new Page.WaitForLoadStateOptions().setTimeout(timeoutMs);
                if (waitNet) {
                    page.waitForLoadState(LoadState.NETWORKIDLE, options);
                } else {
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED, options);
                }
            } catch (TimeoutError e) {
                log.warn("Playwright: [{}] 页面加载超时（{}ms），使用已加载的部分内容", url, timeoutMs);
            }
            return new FetchResult(page.title(), page.content());
        } catch (Exception e) {
            log.error("Playwright: fetch 操作异常: {}", e.getMessage());
            throw new ToolExecutor.ToolExecuteException(
                    "无头浏览器抓取失败: " + e.getMessage() + "。URL: " + url);
        }
    }

    // ==================== 交互模式（按 sessionId 隔离，每个 session 独立的 Playwright 实例） ====================

    public String navigate(String sessionId, String url, int timeoutMs) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.navigate(url, new Page.NavigateOptions().setTimeout(timeoutMs));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                        .setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                log.warn("Playwright: [{}] 页面加载超时（{}ms），使用已加载的部分内容: {}", sessionId, timeoutMs, url);
            }
            return page.title();
        } catch (Exception e) {
            log.error("Playwright: [{}] 导航失败: {}", sessionId, e.getMessage());
            throw new ToolExecutor.ToolExecuteException("浏览器导航失败: " + e.getMessage() + "。URL: " + url);
        }
    }

    public void click(String sessionId, String selector) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.locator(selector).first().click();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("点击元素失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public void type(String sessionId, String selector, String text) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.locator(selector).first().fill(text);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("输入文本失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public void scroll(String sessionId, int deltaY) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.evaluate("window.scrollBy(0, " + deltaY + ")");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("滚动页面失败: " + e.getMessage());
        }
    }

    public void scroll(String sessionId, String selector, int deltaY) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.locator(selector).first().evaluate("el => el.scrollBy(0, " + deltaY + ")");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "滚动元素 [" + selector + "] 失败: " + e.getMessage());
        }
    }

    public void drag(String sessionId, String sourceSelector, String targetSelector) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.locator(sourceSelector).first().dragTo(page.locator(targetSelector).first());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "拖拽失败 [" + sourceSelector + " → " + targetSelector + "]: " + e.getMessage());
        }
    }

    public String screenshot(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            byte[] screenshotBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            return ScaleImageHelper.byteArrayToBase64(screenshotBytes);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("截图失败: " + e.getMessage());
        }
    }

    public String screenshotToFile(String sessionId, String outputPath) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            Path path = Path.of(outputPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(false));
            return path.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("截图保存失败: " + e.getMessage());
        }
    }

    public String getContent(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
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
        Page page = getExistingSession(sessionId).page();
        try {
            return page.title();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取页面标题失败: " + e.getMessage());
        }
    }

    public String getCurrentUrl(String sessionId) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            return page.url();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取当前 URL 失败: " + e.getMessage());
        }
    }

    public void waitForSelector(String sessionId, String selector, int timeoutMs) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
        } catch (TimeoutError e) {
            throw new ToolExecutor.ToolExecuteException(
                    "等待元素超时（" + timeoutMs + "ms）[" + selector + "]: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("等待元素失败 [" + selector + "]: " + e.getMessage());
        }
    }

    public String evaluate(String sessionId, String js) throws ToolExecutor.ToolExecuteException {
        Page page = getExistingSession(sessionId).page();
        try {
            Object result = page.evaluate(js);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("执行 JavaScript 失败: " + e.getMessage());
        }
    }

    // ==================== 内部：Session 级别的完整 Playwright 实例管理 ====================

    /**
     * 获取或创建指定 sessionId 的完整浏览器会话。
     * 每个会话持有独立的 Playwright → Browser → BrowserContext → Page 栈。
     */
    private SessionState getExistingSession(String sessionId) throws ToolExecutor.ToolExecuteException {
        SessionState state = sessions.getIfPresent(sessionId);
        if (state != null) {
            return state;
        }
        log.info("Playwright: [{}] 会话尚未创建，自动初始化...", sessionId);
        return getOrCreateSession(sessionId);
    }

    private SessionState getOrCreateSession(String sessionId) throws ToolExecutor.ToolExecuteException {
        try {
            return sessions.get(sessionId, k -> createSession());
        } catch (RuntimeException e) {
            // Caffeine 会原样抛出 mapping 函数中的 RuntimeException
            throw new ToolExecutor.ToolExecuteException("创建浏览器会话失败: " + e.getMessage());
        }
    }

    /**
     * 创建全新的独立浏览器会话（Playwright → Browser → BrowserContext → Page）。
     * 失败时自动清理已创建的资源。
     */
    private SessionState createSession() {
        Playwright pw = null;
        Browser browser = null;
        BrowserContext context = null;
        try {
            pw = Playwright.create();
            browser = launchBrowser(pw);
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(randomUserAgent())
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN"));
            context.addInitScript(STEALTH_INIT_SCRIPT);
            Page page = context.newPage();
            log.info("Playwright: 会话已创建（缓存 {} 个）", sessions.estimatedSize());
            return new SessionState(pw, browser, context, page);
        } catch (RuntimeException e) {
            // 逐级回滚已创建的资源
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
            if (browser != null) {
                try { browser.close(); } catch (Exception ignored) {}
            }
            if (pw != null) {
                try { pw.close(); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    // ==================== 浏览器生命周期管理 ====================

    /**
     * 启动 Chromium 浏览器。
     * 首次使用时若 Chromium 未安装会自动安装（受锁保护，防止并发安装）。
     */
    private Browser launchBrowser(Playwright playwright) {
        try {
            return playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(LAUNCH_ARGS));
        } catch (Exception e) {
            log.warn("Playwright: Chromium 未安装或启动失败，尝试自动安装... 原因: {}", e.getMessage());
            synchronized (chromiumInstallLock) {
                installChromium();
            }
            return playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(LAUNCH_ARGS));
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

    /**
     * 安全关闭一个会话的全部资源（Page → BrowserContext → Browser → Playwright）。
     */
    private void closeSession(SessionState state) {
        try { state.page().close(); } catch (Exception ignored) {}
        try { state.context().close(); } catch (Exception ignored) {}
        try { state.browser().close(); } catch (Exception ignored) {}
        try { state.playwright().close(); } catch (Exception ignored) {}
    }

    @PreDestroy
    public synchronized void destroy() {
        log.info("Playwright: 正在关闭无头浏览器（{} 个会话）...", sessions.estimatedSize());
        sessions.asMap().forEach((id, state) -> {
            log.info("Playwright: [{}] 关闭会话...", id);
            closeSession(state);
        });
        sessions.invalidateAll();
        fetchExecutor.shutdownNow();
        log.info("Playwright: 已关闭");
    }
}
