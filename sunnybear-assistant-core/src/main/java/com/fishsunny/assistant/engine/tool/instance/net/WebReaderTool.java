package com.fishsunny.assistant.engine.tool.instance.net;

/*
 * @Usage 网页阅读工具 - 使用无头浏览器获取网页并用 AI 提取可读正文，过滤导航栏等噪音
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 17:33
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.NetToolKit;
import com.github.benmanes.caffeine.cache.Cache;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.tool.extension.PlaywrightBrowserService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import org.jsoup.select.NodeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(NetToolKit.class)
@ConditionalOnExpression("${engine.tool.net.enable:true} && ${engine.tool.net.web-reader.enable:true}")
public class WebReaderTool implements ToolHandler {

    public static final String NAME = "web_reader_tool";
    public static final String SETTINGS = "web_reader_tool_settings";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings summarySettings;
    private final ChatHttpHandler chatHttpHandler;
    private final Settings settings;
    private final PlaywrightBrowserService playwrightBrowserService;
    private final Cache<String, Object> cache;

    private static final Logger log = LoggerFactory.getLogger(WebReaderTool.class);

    public WebReaderTool(ObjectMapper objectMapper,
                         @Qualifier(AISettings.SUMMARY) AISettings summarySettings,
                         ChatHttpHandler chatHttpHandler,
                         @Qualifier(SETTINGS) Settings settings,
                         PlaywrightBrowserService playwrightBrowserService,
                         Cache<String, Object> cache
                         ) {
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        获取网页正文内容。fast 模式快速提取全文，quality 模式可按 target 定向提取关键内容。""")
                .setRequired(List.of("url"));
        ToolRegister.Parameters urlParam = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("目标网页的 URL 地址");
        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("""
                        【quality 模式专用】描述你希望重点提取的内容。\
                        例如 '提取核心论点'、'列出所有 API 端点'。fast 模式下忽略。""");
        ToolRegister.Parameters modeParam = new ToolRegister.Parameters()
                .setParameterName("mode")
                .setType("string")
                .setDescription("""
                        提取模式，默认 'quality'。\
                        'fast'：快速全文提取，不支持 target。\
                        'quality'：系统按 target 定向提取。""");

        ToolRegister.Parameters waitNetParam = new ToolRegister.Parameters()
                .setParameterName("waitNet")
                .setType("boolean")
                .setDescription("""
                       是否等待网络请求完成再提取。\
                       如果目标网页有大量异步加载的内容（如 SPA 页面），建议设置为 true。""");

        register.setParameters(List.of(urlParam, targetParam, modeParam, waitNetParam));

        this.objectMapper = objectMapper;
        this.summarySettings = summarySettings;
        this.chatHttpHandler = chatHttpHandler;
        this.settings = settings;
        this.playwrightBrowserService = playwrightBrowserService;
        this.cache = cache;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数 url 不能为空");
            }
            Object present = cache.getIfPresent(arguments.getUrl());
            if (present instanceof ToolExecutor.ToolExecuteResponse response) {
                return response;
            }

            String mode = arguments.getMode();
            if (!StringUtils.hasText(mode)) {
                mode = "quality";
            } else {
                mode = mode.toLowerCase();
            }
            if (!"fast".equals(mode) && !"quality".equals(mode)) {
                throw new ToolExecutor.ToolExecuteException(
                        "无效的 mode 参数: " + mode + "，仅支持 'fast' 或 'quality'");
            }

            // 始终使用无头浏览器渲染
            PlaywrightBrowserService.FetchResult result = playwrightBrowserService.fetch(
                    arguments.getUrl(), settings.getBrowserTimeoutMs(), Boolean.TRUE.equals(arguments.getWaitNet()));
            Document document = Jsoup.parse(result.htmlContent(), arguments.getUrl());
            String pageTitle = result.title();

            // 预处理：删除一眼就能判断的无关内容，减少后续处理的数据量
            cleanHtml(document);

            // 先用 Tika 将 HTML 洗成纯文本（两种模式共用）
            String cleanedText = tikaExtractText(document);

            String resultText;
            if ("fast".equals(mode)) {
                // 快速模式：Tika 提取后直接返回，不使用 LLM
                resultText = formatFastResult(pageTitle, arguments.getTarget(), cleanedText);
            } else {
                // 质量模式：Tika 洗过的纯文本再喂给 AI 智能提取
                String target = StringUtils.hasText(arguments.getTarget()) ? arguments.getTarget() : "默认提取有意义的文本";
                resultText = summarizeDocument(pageTitle, cleanedText, target);
            }
            ToolExecutor.ToolExecuteResponse response = new ToolExecutor.ToolExecuteResponse(name(), resultText);
            cache.put(arguments.getUrl(), response);
            return response;
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("获取网页失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    // ==================== 共享管线：HTML 清理 + 正文提取 ====================

    /**
     * 使用 Apache Tika 从已清理的 HTML 中提取纯文本正文（两种模式共用）
     */
    private String tikaExtractText(Document document) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1); // 不限制输出长度
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, "text/html; charset=UTF-8");
        HtmlParser parser = new HtmlParser();
        parser.parse(
                new ByteArrayInputStream(document.html().getBytes(StandardCharsets.UTF_8)),
                handler, metadata, new ParseContext());
        return handler.toString().trim();
    }

    /**
     * 快速模式：格式化 Tika 提取结果并返回
     */
    private String formatFastResult(String pageTitle, String target, String extractedText) {
        StringBuilder result = new StringBuilder();
        result.append("## ").append(StringUtils.hasText(pageTitle) ? pageTitle : "无标题").append("\n\n");
        result.append("> 快速模式 (fast mode) — 基于 Apache Tika 正文抽取\n");
        if (StringUtils.hasText(target)) {
            result.append("> ⚠️ 注意：fast 模式下 target 参数不可用，已忽略指定的目标：「")
                    .append(target).append("」\n");
        }
        result.append("\n").append(extractedText);
        return result.toString();
    }

    /**
     * 质量模式：使用 AI 对 Tika 洗过的纯文本进行智能摘要提取
     */
    private String summarizeDocument(String pageTitle, String cleanedText, String target) throws Exception {
        String userPrompt = """
                [页面标题]
                ${title}
                [页面正文（已预先清洗）]
                ${content}
                [任务目标]
                ${target}
                """.replace("${title}", StringUtils.hasText(pageTitle) ? pageTitle : "无")
                .replace("${content}", cleanedText)
                .replace("${target}", target);
        ChatRequest request = new ChatRequest()
                .setMessages(List.of(new ChatMessage().system(summarySettings.getPrompt()), new ChatMessage().user(userPrompt)))
                .loadSettings(summarySettings);
        AtomicReference<String> afterResolve = new AtomicReference<>("");
        chatHttpHandler.translate(UUID.randomUUID().toString(), summarySettings.getAdapterName(), request,
                summarySettings.getStream(),
                null,
                (result, lastRes) -> afterResolve.set(result.content()));
        return afterResolve.get();
    }

    /**
     * 预处理 HTML，删除噪音内容，减少后续 Tika/AI 处理的数据量。
     * <p>
     * 优化要点：减少 DOM 遍历次数，将多次 select 合并，空元素/注释删除合并为单次树遍历。
     */
    private void cleanHtml(Document document) {
        // 1. 合并删除：标签选择器 + 语义化非内容标签 → 一次遍历
        document.select(
                "script, style, noscript, head, link, meta, iframe, svg, " +
                "nav, footer, aside"
        ).remove();

        // 2. 合并删除：class/id 噪音关键词 → 一次遍历（原来每个模式一次遍历，共 18 次）
        String[] noisePatterns = {
                "sidebar", "comment", "widget", "related", "recommend",
                "share", "social", "cookie", "popup", "sponsor", "promo",
                "advertisement", "advert", "navbar", "breadcrumb", "pagination",
                "copyright", "subscribe", "newsletter"
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < noisePatterns.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("[class*=").append(noisePatterns[i]).append("], ");
            sb.append("[id*=").append(noisePatterns[i]).append("]");
        }
        document.select(sb.toString()).remove();

        // 3. 合并删除：隐藏元素 → 一次遍历
        document.select(
                "[style*=\"display:none\"], [style*=\"display: none\"], " +
                "[style*=\"visibility:hidden\"], [style*=\"visibility: hidden\"], " +
                "[aria-hidden=\"true\"], [hidden]"
        ).remove();

        // 4. 单次遍历：删除注释 + 自底向上删除空元素（原来两次遍历，且空元素是 O(n²) while 循环）
        removeCommentsAndEmptyElements(document);
    }

    /**
     * 单次 DOM 遍历同时完成：①删除注释节点  ②自底向上删除空元素。
     * <p>
     * 利用 Jsoup {@link NodeFilter} 的 tail() 回调天然是自底向上（子节点先于父节点触发 tail），
     * 子节点被移除后父节点自动变为空元素，一次遍历即可完成，复杂度 O(n)。
     * 替代原来 O(n²) 的 while 循环 + 每次全量 {@code select("*")} 的做法。
     */
    private void removeCommentsAndEmptyElements(Document document) {
        document.filter(new NodeFilter() {
            @Override
            public FilterResult head(@NonNull Node node, int depth) {
                // 注释节点直接移除，不遍历其子节点（注释无子节点，但返回 REMOVE 跳过无意义的深度遍历）
                if (node instanceof Comment) {
                    return FilterResult.REMOVE;
                }
                return FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(@NonNull Node node, int depth) {
                if (node instanceof Element) {
                    Element el = (Element) node;
                    // 保留自闭合或有意义的空元素
                    if (el.is("br, hr, img, input, textarea, area, base, col, embed, source, track, wbr")) {
                        return FilterResult.CONTINUE;
                    }
                    // tail 触发时子元素已全部处理完毕，若此时无文本且无剩余子元素则可安全移除
                    if (el.ownText().trim().isEmpty() && el.children().isEmpty()) {
                        return FilterResult.REMOVE;
                    }
                }
                return FilterResult.CONTINUE;
            }
        });
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** 无头浏览器页面加载超时（毫秒），默认15000 */
        private Integer browserTimeoutMs = 15000;
    }

    @Data
    private static class Arguments {
        private String url;
        private String target;
        private String mode;
        private Boolean waitNet = false;
        public String getKey() {
            return url + ":" + target + ":" + mode;
        }
    }
}
