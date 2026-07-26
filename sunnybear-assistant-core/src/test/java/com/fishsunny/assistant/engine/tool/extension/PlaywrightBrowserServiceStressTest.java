package com.fishsunny.assistant.engine.tool.extension;

import com.fishsunny.assistant.engine.tool.ToolExecutor.ToolExecuteException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlaywrightBrowserService 可靠性压力测试。
 * <p>
 * 架构说明：PlaywrightBrowserService 使用全局串行化设计，所有操作共享一把锁。
 * 本测试多线程提交请求，验证在排队等待场景下的稳定性和自动容灾能力。
 * <p>
 * 用法：直接跑 main()，不依赖 Spring 容器。
 */
public class PlaywrightBrowserServiceStressTest {

    /** 并发线程数 */
    private static final int THREADS = 8;
    /** 每线程请求轮次 */
    private static final int ROUNDS_PER_THREAD = 5;
    /** fetch 超时（毫秒） */
    private static final int TIMEOUT_MS = 20_000;

    /**
     * 测试 URL 池 —— 覆盖不同类型站点：
     * <ul>
     *   <li>静态文档 / 简单页面</li>
     *   <li>国内新闻门户（JS 渲染重）</li>
     *   <li>SPA / React 页面</li>
     *   <li>可能触发反爬的站点</li>
     *   <li>慢响应 / 大体积页面</li>
     * </ul>
     */
    private static final List<String> URLS = List.of(
            // 静态 / 简单页面
            "https://httpbin.org/html",
            "https://example.com",
            // 国内新闻（JS 重）
            "https://news.mydrivers.com/1/1033/1033367.htm",
            "https://www.163.com",
            // SPA / 交互重
            "https://react.dev",
            "https://www.baidu.com",
            // 可能触发反爬
            "https://www.zhihu.com",
            "https://www.douyin.com",
            // 慢 / 大体量
            "https://www.bilibili.com",
            "https://httpbin.org/delay/5"
    );

    // ==================== 统计 ====================

    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger timeoutCount = new AtomicInteger(0);
    private static final AtomicInteger adoptErrorCount = new AtomicInteger(0);
    private static final AtomicInteger closedErrorCount = new AtomicInteger(0);
    private static final AtomicInteger otherErrorCount = new AtomicInteger(0);

    /** 所有错误详情（线程安全） */
    private static final ConcurrentLinkedQueue<String> errorDetails = new ConcurrentLinkedQueue<>();
    /** 每次请求的耗时（毫秒） */
    private static final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    // ==================== main ====================

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  PlaywrightBrowserService 可靠性压力测试（全局串行化架构）");
        System.out.println("  线程数: " + THREADS + " | 每线程轮次: " + ROUNDS_PER_THREAD
                + " | 总请求: " + (THREADS * ROUNDS_PER_THREAD));
        System.out.println("  超时: " + TIMEOUT_MS + "ms | URL 池大小: " + URLS.size());
        System.out.println("  注意: 多线程并发提交，Browser 内部串行执行");
        System.out.println("=".repeat(70));

        PlaywrightBrowserService service = new PlaywrightBrowserService();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        Instant start = Instant.now();

        // --- 全并发提交 ---
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger taskId = new AtomicInteger(0);
        for (int t = 0; t < THREADS; t++) {
            futures.add(executor.submit(() -> {
                for (int r = 0; r < ROUNDS_PER_THREAD; r++) {
                    int id = taskId.getAndIncrement();
                    String url = URLS.get(id % URLS.size());
                    runOne(service, id, url);
                }
            }));
        }

        // --- 等待全部完成 ---
        for (Future<?> f : futures) {
            try {
                f.get(TIMEOUT_MS * ROUNDS_PER_THREAD + 60_000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                System.err.println("⚠ 有线程未在预期时间内完成，强制取消");
                f.cancel(true);
            } catch (Exception e) {
                System.err.println("⚠ 线程执行异常: " + e.getMessage());
            }
        }

        executor.shutdownNow();
        Instant end = Instant.now();

        // --- 报告 ---
        printReport(start, end);

        // --- 清理 ---
        System.out.println("\n正在关闭浏览器...");
        service.destroy();
        System.out.println("浏览器已关闭。");
    }

    // ==================== 单次 fetch ====================

    private static void runOne(PlaywrightBrowserService service, int id, String url) {
        Instant t0 = Instant.now();
        try {
            PlaywrightBrowserService.FetchResult result = service.fetch(url, TIMEOUT_MS, true);
            long ms = Duration.between(t0, Instant.now()).toMillis();
            latencies.add(ms);
            successCount.incrementAndGet();
            System.out.printf("  [%03d] ✅ %5dms | %s | title=%s%n",
                    id, ms, url, truncate(result.title(), 40));
        } catch (ToolExecuteException e) {
            long ms = Duration.between(t0, Instant.now()).toMillis();
            latencies.add(ms);
            String rawMsg = e.getMessage();
            categorizeAndRecord(id, url, rawMsg, ms);
        }
    }

    private static void categorizeAndRecord(int id, String url, String msg, long ms) {
        if (msg.contains("Timeout") || msg.contains("超时") || msg.contains("TimeoutError")) {
            timeoutCount.incrementAndGet();
            System.out.printf("  [%03d] ⏱ %5dms | TIMEOUT | %s%n", id, ms, url);
        } else if (msg.contains("__adopt__")) {
            adoptErrorCount.incrementAndGet();
            System.out.printf("  [%03d] 👻 %5dms | ADOPT   | %s | %s%n", id, ms, url, truncate(msg, 100));
            errorDetails.add("[ADOPT] " + url + " → " + msg);
        } else if (msg.contains("closed") || msg.contains("Closed")) {
            closedErrorCount.incrementAndGet();
            System.out.printf("  [%03d] 💀 %5dms | CLOSED  | %s | %s%n", id, ms, url, truncate(msg, 100));
            errorDetails.add("[CLOSED] " + url + " → " + msg);
        } else {
            otherErrorCount.incrementAndGet();
            System.out.printf("  [%03d] ❌ %5dms | OTHER   | %s | %s%n", id, ms, url, truncate(msg, 100));
            errorDetails.add("[OTHER] " + url + " → " + msg);
        }
    }

    // ==================== 报告 ====================

    private static void printReport(Instant start, Instant end) {
        int total = THREADS * ROUNDS_PER_THREAD;
        int success = successCount.get();
        int errors = timeoutCount.get() + adoptErrorCount.get() + closedErrorCount.get() + otherErrorCount.get();
        long totalMs = Duration.between(start, end).toMillis();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  测试报告");
        System.out.println("=".repeat(70));

        System.out.printf("  总耗时    : %dms (%.1fs)%n", totalMs, totalMs / 1000.0);
        System.out.printf("  总请求    : %d%n", total);
        System.out.printf("  成功      : %d (%.1f%%)%n", success, 100.0 * success / total);
        System.out.println("  ─────────────────────────────");
        System.out.printf("  超时      : %d (%.1f%%)%n",
                timeoutCount.get(), 100.0 * timeoutCount.get() / total);
        System.out.printf("  __adopt__ : %d (%.1f%%)  ← 目标问题（修复前高频）%n",
                adoptErrorCount.get(), 100.0 * adoptErrorCount.get() / total);
        System.out.printf("  closed    : %d (%.1f%%)%n",
                closedErrorCount.get(), 100.0 * closedErrorCount.get() / total);
        System.out.printf("  其他错误  : %d (%.1f%%)%n",
                otherErrorCount.get(), 100.0 * otherErrorCount.get() / total);

        // 延迟统计
        if (!latencies.isEmpty()) {
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            System.out.println("  ─────────────────────────────");
            System.out.printf("  延迟 min  : %dms%n", sorted.get(0));
            System.out.printf("  延迟 p50  : %dms%n", sorted.get(sorted.size() / 2));
            System.out.printf("  延迟 p95  : %dms%n", sorted.get((int) (sorted.size() * 0.95)));
            System.out.printf("  延迟 max  : %dms%n", sorted.get(sorted.size() - 1));
        }

        // 错误详情
        if (!errorDetails.isEmpty()) {
            System.out.println("  ─────────────────────────────");
            System.out.println("  错误详情（前 20 条）:");
            errorDetails.stream().limit(20).forEach(e -> System.out.println("    " + e));
            if (errorDetails.size() > 20) {
                System.out.println("    ... 共 " + errorDetails.size() + " 条");
            }
        }

        System.out.println("=".repeat(70));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        String cleaned = s.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen - 3) + "...";
    }
}
