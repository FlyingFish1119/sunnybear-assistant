/**
 * Markdown 渲染工具
 *
 * 将 markdown 文本转为 HTML，支持：
 *   - highlight.js 代码高亮（通过 marked 自定义渲染器）
 *   - Mermaid 图表（输出占位 div，调用方自行触发 mermaid.run）
 *   - KaTeX 数学公式（块级 $$...$$ / 行内 $...$）
 *
 * 依赖（全局）：marked, hljs, katex
 *
 * 流式渲染说明：
 *   render(text)       —— 整段渲染，走 LRU 缓存；适合非流式内容（历史消息、工具弹窗）。
 *   streamBlocks(text) —— 把文本按「顶层块」（空行分隔，围栏代码块内部的空行不切）切成数组，
 *                        逐块给出 HTML。调用方用 v-for + :key 渲染这些块：
 *                        前缀块的 HTML 字符串在后续 chunk 里不再变化，Vue 会跳过对它们的
 *                        innerHTML 赋值，DOM 节点得以保留；只有最后一块（还在追加中）每帧重算。
 *                        已闭合的代码块 / 表格 / 列表因此不会被反复销毁重建。
 *
 * Mermaid 说明：
 *   render / streamBlocks 仍同步返回字符串：命中 code->SVG 缓存就输出 SVG，否则输出源码占位
 *   并登记一次去抖异步渲染。渲染成功后通过 onMermaidRendered 通知调用方重渲染，把占位换成
 *   SVG。因为 v-html 只在字符串变化时才写 DOM，已渲染块不会被后续 chunk 覆盖，从而支持流式
 *   增量出图，且不会每 chunk 重建 DOM。
 *   renderMermaid(container) 保留给直接插入 .mermaid 占位的场景（聊天外的插件页）与回合结束兜底。
 *   utils/mermaid.js 是它的兼容壳。
 */
/**
 * 消息内原始 HTML 的 Shadow DOM 沙盒。
 *
 * 为什么需要：marked v15 移除了 sanitize，模型输出的 raw HTML 会经 v-html 进入页面。
 * 一旦出现整页 <style>，其选择器会污染全局样式，整个会话区直接不可用。
 *
 * 做法：块级原始 HTML 不直接拼进消息，而是塞进自定义元素
 * <md-html-block data-html="...">，由本元素把内容挂进自己的 shadow root：
 *   · <style> 只在 shadow 树内生效，不会外泄到页面（CSS 作用域隔离）；
 *   · 宿主加 contain:layout paint，把 position:fixed 之类的覆盖限制在宿主盒内，防整屏遮挡；
 *   · 再对 shadow 内容做一层轻量加固：删掉 iframe/object/embed/form 等会覆盖或外联的元素，
 *     剥掉 on* 事件属性与 javascript:/data: 等危险 URL。
 * 注意：这不是 DOMPurify 级别的安全边界（不做 mXSS 对抗），
 * 定位是「模型发癫也不会把页面/会话搞挂」。本工具自己生成的 code/mermaid/katex
 * HTML 不走这里，完全不受影响。
 */
(function registerMarkdownSandbox() {
    if (typeof customElements === 'undefined' || customElements.get('md-html-block')) return;

    var BASE_CSS = '<style>'
        + ':host{display:block;color:inherit;font:inherit;}'
        + 'a{color:var(--main-color,#ffa07a);}'
        + 'img,video{max-width:100%;}'
        + '</style>';

    /** 会整屏覆盖 / 外联加载的元素，直接删掉 */
    var DROP_TAGS = { script: 1, iframe: 1, object: 1, embed: 1, link: 1, meta: 1, base: 1, form: 1 };
    /** 需要校验协议的属性 */
    var URL_ATTRS = { href: 1, src: 1, 'xlink:href': 1, formaction: 1, action: 1 };
    var DANGEROUS_URL_RE = /^(?:javascript|vbscript|data|blob):/i;

    /** 轻量加固：删危险元素、on* 事件属性、危险协议 URL（只在 shadow 树内执行） */
    function harden(root) {
        var nodes = root.querySelectorAll('*');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (DROP_TAGS[el.tagName.toLowerCase()]) {
                el.remove();
                continue;
            }
            var attrs = el.attributes;
            for (var j = attrs.length - 1; j >= 0; j--) {
                var name = attrs[j].name.toLowerCase();
                if (name.indexOf('on') === 0) {
                    el.removeAttribute(attrs[j].name);
                } else if (URL_ATTRS[name]
                    && DANGEROUS_URL_RE.test(attrs[j].value.replace(/[\u0000-\u0020\u007F]+/g, ''))) {
                    el.removeAttribute(attrs[j].name);
                }
            }
        }
    }

    class MdHtmlBlock extends HTMLElement {
        connectedCallback() {
            if (this._mdShadow) return;
            var html = this.getAttribute('data-html') || '';
            this.removeAttribute('data-html');
            var root = this.attachShadow({ mode: 'open' });
            root.innerHTML = BASE_CSS + html;
            harden(root);
            this._mdShadow = root;
        }
    }
    customElements.define('md-html-block', MdHtmlBlock);

    // 宿主样式：外层 !important 会压过模型 shadow 里的 :host 规则，
    // 防止模型用 :host{position:fixed} 让宿主自身脱离文档流、盖住界面。
    if (!document.getElementById('md-html-block-style')) {
        var style = document.createElement('style');
        style.id = 'md-html-block-style';
        style.textContent = 'md-html-block{display:block;position:static !important;contain:layout paint;}';
        (document.head || document.documentElement).appendChild(style);
    }
})();

const MarkdownUtils = (function () {

    /* ---- 编辑工具 diff 渲染辅助 ---- */

    /** HTML 转义（diff 分支自行拼 HTML 用） */
    function escapeHtml(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /** 属性值转义（比 escapeHtml 多转义引号，供自行拼标签时用） */
    function escapeAttr(s) {
        return escapeHtml(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /**
     * URL 协议白名单：拒绝 javascript: / vbscript: / data: / blob: 等危险协议。
     * marked v15 的 cleanUrl 只做 encodeURI，不拦协议，所以这里自行兜一层。
     * 先把空白与控制字符去掉再判前缀，防止 `java\tscript:` 这类混淆写法绕过。
     *
     * @param {string} url 原始链接
     * @returns {string|null} 安全则原样返回，危险协议返回 null
     */
    function safeUrl(url) {
        if (url == null) return '';
        var probe = String(url).replace(/[\u0000-\u0020\u007F]+/g, '').toLowerCase();
        return /^(?:javascript|vbscript|data|blob):/.test(probe) ? null : url;
    }

    /** file/proxy URL 重写：补上 BASE_PATH（云上部署时页面可能不在根路径） */
    function rewriteProxyUrls(html) {
        return html.replace(/(src|href)="(\/?)(file\/proxy\?[^"]+)"/g,
            function (match, attr, slash, rest) {
                return attr + '="' + API.BASE_PATH + rest + '"';
            });
    }

    /**
     * 行内原始 HTML 的保守白名单：只放行纯格式标签，属性仅保留过滤后的 style。
     * 行内 token 是「单个标签」（开/闭各一个），无法像块级那样整段进 Shadow DOM，
     * 所以退而求其次：白名单标签名 + 属性白名单，其余一律转义。
     * style 只放行表现型声明（颜色 / 字体 / 边框等），并丢掉 position、url()、表达式、
     * 反斜杠转义等可能覆盖界面或外联的写法；on* / class 等其余属性一律丢弃。
     * 锚定整段匹配也顺带挡住了 <span/onload=x> 这种浏览器会当成属性的怪异写法。
     */
    var SAFE_INLINE_TAGS = {
        span: 1, b: 1, strong: 1, i: 1, em: 1, u: 1, s: 1, del: 1, ins: 1, mark: 1,
        small: 1, sub: 1, sup: 1, code: 1, kbd: 1, samp: 1, var: 1, abbr: 1, cite: 1,
        q: 1, br: 1, wbr: 1, time: 1, ruby: 1, rt: 1, rp: 1
    };
    var INLINE_TAG_RE = /^<(\/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s[^>]*)?)\/?>$/;
    var INLINE_ATTR_RE = /([^\s"'<>\/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;

    /** 表现型 CSS 属性白名单：不含 position / z-index / display / float / transform 等 */
    var SAFE_STYLE_PROPS = {
        color: 1, background: 1, 'background-color': 1,
        font: 1, 'font-family': 1, 'font-size': 1, 'font-style': 1,
        'font-variant': 1, 'font-weight': 1,
        'line-height': 1, 'letter-spacing': 1, 'word-spacing': 1,
        'text-align': 1, 'text-indent': 1, 'text-shadow': 1, 'text-transform': 1,
        'text-decoration': 1, 'text-decoration-color': 1, 'text-decoration-line': 1,
        'text-decoration-style': 1,
        'white-space': 1, 'vertical-align': 1, opacity: 1,
        border: 1, 'border-color': 1, 'border-style': 1, 'border-width': 1,
        'border-radius': 1, 'box-shadow': 1,
        padding: 1, margin: 1
    };
    /** 值里出现这些就整条声明丢弃：外联加载、表达式执行、CSS 转义混淆 */
    var UNSAFE_STYLE_VALUE_RE = /(?:url\s*\(|expression\s*\(|javascript:|@import|[\\<>])/i;

    function sanitizeInlineStyle(style) {
        if (!style) return '';
        var decls = String(style).split(';');
        var out = [];
        for (var i = 0; i < decls.length; i++) {
            var idx = decls[i].indexOf(':');
            if (idx === -1) continue;
            var prop = decls[i].slice(0, idx).trim().toLowerCase();
            var value = decls[i].slice(idx + 1).trim();
            if (!prop || !value || !SAFE_STYLE_PROPS[prop]) continue;
            if (UNSAFE_STYLE_VALUE_RE.test(value)) continue;
            out.push(prop + ':' + value);
        }
        return out.join(';');
    }

    /** 从标签属性串里取出 style 的值（支持双引号 / 单引号 / 无引号） */
    function extractInlineStyle(attrText) {
        if (!attrText) return '';
        var re = new RegExp(INLINE_ATTR_RE.source, 'g');
        var match;
        while ((match = re.exec(attrText)) !== null) {
            if (match[1].toLowerCase() !== 'style') continue;
            if (match[2] !== undefined) return match[2];
            if (match[3] !== undefined) return match[3];
            return match[4] !== undefined ? match[4] : '';
        }
        return '';
    }

    function sanitizeInlineHtml(raw) {
        var m = raw.match(INLINE_TAG_RE);
        if (!m || !SAFE_INLINE_TAGS[m[2].toLowerCase()]) return escapeHtml(raw);
        var name = m[2].toLowerCase();
        if (m[1]) return '</' + name + '>';
        var style = sanitizeInlineStyle(extractInlineStyle(m[3]));
        return style ? '<' + name + ' style="' + escapeAttr(style) + '">' : '<' + name + '>';
    }

    /** 是否为编辑工具产出的 diff：存在以 + / - 开头的「行号|内容」行 */
    function isDiffText(text) {
        return /^[+\-]\s*\d+\| /m.test(text);
    }

    /** 把 diff 文本渲染为逐行着色（+ 绿背景 / - 红背景，代码仍走高亮，标题保留原语言） */
    function renderDiff(text, lang) {
        var supported = lang && hljs.getLanguage(lang);
        var langLabel = lang || 'diff';
        var copyBtn = '<button type="button" class="code-copy-btn" title="复制代码"><i data-lucide="copy"></i></button>';
        var body = text.split('\n').map(function (line) {
            if (line === '') return '';
            var cls = '';
            if (line.charAt(0) === '+') {
                cls = ' diff-add';
            } else if (line.charAt(0) === '-') {
                cls = ' diff-del';
            }
            // 拆出「标记 + 行号| 」前缀，代码部分单独走高亮
            var m = line.match(/^([+\- ]\s*\d+\| )([\s\S]*)$/);
            var gutter = m ? m[1] : '';
            var code = m ? m[2] : line;
            var highlighted;
            try {
                highlighted = supported ? hljs.highlight(code, { language: lang }).value : escapeHtml(code);
            } catch (e) {
                highlighted = escapeHtml(code);
            }
            return '<span class="diff-line' + cls + '">'
                + '<span class="diff-gutter">' + escapeHtml(gutter) + '</span>'
                + '<span class="diff-code-line">' + highlighted + '</span>'
                + '</span>';
        }).join('\n');
        return '<div class="code-block-wrapper">'
            + '<div class="code-block-header">'
            + '<span class="code-block-lang">' + escapeHtml(langLabel) + '</span>'
            + copyBtn
            + '</div>'
            + '<pre class="diff-pre"><code class="diff-code">' + body + '</code></pre></div>';
    }

    /* ---- 配置 marked ---- */
    marked.use({
        // 单个换行也渲染成 <br>（GFM 行为），避免必须敲两次回车才换行
        breaks: true,
        renderer: {
            /**
             * 原始 HTML：
             *   · 块级 —— 整段丢进 <md-html-block> 沙盒（Shadow DOM），
             *     <style> 不外泄、position:fixed 覆盖被 contain 限制；
             *   · 行内 —— 白名单标签名 + 剥属性（行内没法整段进 Shadow DOM）。
             * 本工具自己生成的 code/mermaid/katex HTML 走各自 renderer，不经过这里。
             */
            html: function (obj) {
                if (obj.block) {
                    return '<md-html-block data-html="'
                        + escapeAttr(rewriteProxyUrls(obj.text))
                        + '"></md-html-block>';
                }
                return sanitizeInlineHtml(obj.text);
            },
            /**
             * 链接：协议白名单。默认渲染器对危险协议只会退化成纯文本，这里保持一致，
             * 但额外拦掉 data:/blob: 等。
             */
            link: function (obj) {
                var href = safeUrl(obj.href);
                var text = this.parser.parseInline(obj.tokens);
                if (href === null) return text;
                return '<a href="' + escapeAttr(href) + '"'
                    + (obj.title ? ' title="' + escapeAttr(obj.title) + '"' : '')
                    + '>' + text + '</a>';
            },
            /** 图片：同样走协议白名单，危险协议退化为转义后的 alt 文本 */
            image: function (obj) {
                var src = safeUrl(obj.href);
                var alt = obj.tokens
                    ? this.parser.parseInline(obj.tokens, this.parser.textRenderer)
                    : obj.text;
                if (src === null) return escapeHtml(alt);
                return '<img src="' + escapeAttr(src) + '" alt="' + escapeAttr(alt) + '"'
                    + (obj.title ? ' title="' + escapeAttr(obj.title) + '"' : '')
                    + '>';
            },
            code: function (obj) {
                var text = obj.text;
                var lang = obj.lang;
                if (lang === 'mermaid') {
                    return renderMermaidBlock(text);
                }
                // 编辑工具产出的 diff：按行 + / - 上背景色（代码仍走语言高亮）
                if (isDiffText(text)) {
                    return renderDiff(text, lang);
                }
                var langLabel = lang || '';
                var supported = lang && hljs.getLanguage(lang);
                var highlighted = supported
                    ? hljs.highlight(text, { language: lang }).value
                    : hljs.highlightAuto(text).value;
                var copyBtn = '<button type="button" class="code-copy-btn" title="复制代码"><i data-lucide="copy"></i></button>';
                return '<div class="code-block-wrapper">'
                    + '<div class="code-block-header">'
                    + (langLabel ? '<span class="code-block-lang">' + langLabel + '</span>' : '')
                    + copyBtn
                    + '</div>'
                    + '<pre><code class="hljs' + (supported ? ' language-' + lang : '') + '">'
                    + highlighted
                    + '</code></pre></div>';
            }
    }
    });

    /* ---- 缓存（LRU）：非流式内容与流式里已闭合的块共用 ---- */
    var CACHE_LIMIT = 120;
    var _cache = new Map();

    /** 同一段文本的重复调用（组件因其他原因重渲染）直接返回上次的分块结果 */
    var BLOCKS_MEMO_LIMIT = 4;
    var _blocksMemo = new Map();

    /** 命中就把该条挪到队尾（最近使用），未命中返回 undefined */
    function cacheGet(text) {
        var hit = _cache.get(text);
        if (hit === undefined) return undefined;
        _cache.delete(text);
        _cache.set(text, hit);
        return hit;
    }

    /** 超出上限时淘汰最早插入的一条，避免流式期间缓存无限膨胀 */
    function cacheSet(text, html) {
        if (_cache.size >= CACHE_LIMIT) {
            var oldest = _cache.keys().next();
            if (!oldest.done) _cache.delete(oldest.value);
        }
        _cache.set(text, html);
    }

    function clearCache() {
        _cache = new Map();
        _blocksMemo = new Map();
    }

    /* ---- 渲染 ---- */

    /** 解析 markdown → HTML（纯计算，无缓存；流式活动块每帧都走这里） */
    function renderHtml(text) {
        var html = '';
        try {
            // 保护 LaTeX 公式：先提取公式 → 占位符 → markdown 渲染 → KaTeX 还原
            var mathBlocks = [];
            // 块级公式：$$...$$
            var processed = text.replace(/\$\$([\s\S]*?)\$\$/g, function (_match, formula) {
                var id = mathBlocks.length;
                mathBlocks.push({ type: 'block', formula: formula.trim() });
                return '\x00MB' + id + '\x00';
            });
            // 行内公式：$...$（不匹配 $$）
            processed = processed.replace(/(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)/g, function (_match, formula) {
                var id = mathBlocks.length;
                mathBlocks.push({ type: 'inline', formula: formula.trim() });
                return '\x00MI' + id + '\x00';
            });
            // Markdown 渲染
            html = marked.parse(processed);
            // 重写 file/proxy URL，补上 BASE_PATH（云上部署时页面可能不在根路径）
            html = rewriteProxyUrls(html);
            // 还原公式：用 KaTeX 渲染替换占位符
            mathBlocks.forEach(function (block, id) {
                var placeholder = (block.type === 'block' ? '\x00MB' : '\x00MI') + id + '\x00';
                var rendered = katex.renderToString(block.formula, {
                    displayMode: block.type === 'block',
                    throwOnError: false
                });
                html = html.replace(placeholder, rendered);
            });
        } catch (e) {
            // 解析异常时也绝不能把原文直接交给 v-html：转义后再按换行展开，宁可显示纯文本
            html = escapeHtml(text).replace(/\n/g, '<br>');
        }
        return html;
    }

    /** 单段渲染（带缓存）：非流式内容、流式里已闭合的块都走这里 */
    function render(text) {
        if (!text) return '';
        // 每次 Vue 重渲染可能对同一 content 重复调用，缓存避免无意义计算
        var cached = cacheGet(text);
        if (cached !== undefined) return cached;
        var html = renderHtml(text);
        cacheSet(text, html);
        return html;
    }

    /* ---- 流式分块 ---- */

    /** 围栏起始行：``` 或 ~~~（缩进不超过 3 空格），后面可跟语言标识 */
    var FENCE_OPEN_RE = /^\s{0,3}(`{3,}|~{3,})(.*)$/;
    /** 围栏结束行：只有围栏字符与空白 */
    var FENCE_CLOSE_RE = /^\s{0,3}(`{3,}|~{3,})\s*$/;

    /**
     * 把 markdown 文本切成顶层块：空行是切分点，围栏代码块内部的空行不算。
     * 只做「追加」假设下的安全切分——切出来的块文本在后续 chunk 里不会再变。
     *
     * @param {string} text 流式累计的 markdown 文本
     * @returns {string[]} 块的原始文本（不含分隔空行）
     */
    function splitTopLevelBlocks(text) {
        var lines = text.split('\n');
        var blocks = [];
        var buf = [];
        var inFence = false;
        var fenceChar = '';
        var fenceLen = 0;

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];

            if (inFence) {
                var closing = line.match(FENCE_CLOSE_RE);
                if (closing && closing[1].charAt(0) === fenceChar && closing[1].length >= fenceLen) {
                    inFence = false;
                    fenceChar = '';
                    fenceLen = 0;
                }
                buf.push(line);
                continue;
            }

            var opening = line.match(FENCE_OPEN_RE);
            if (opening) {
                inFence = true;
                fenceChar = opening[1].charAt(0);
                fenceLen = opening[1].length;
                buf.push(line);
                continue;
            }

            if (line.trim() === '') {
                // 空行把当前累积的内容收成一个已闭合的块
                if (buf.length > 0) {
                    blocks.push(buf.join('\n'));
                    buf = [];
                }
                continue;
            }

            buf.push(line);
        }

        if (buf.length > 0) {
            blocks.push(buf.join('\n'));
        }
        return blocks;
    }

    /**
     * 流式渲染入口：返回可直接交给 v-for 的块数组。
     *   - 前缀块（已被空行闭合）走 render 缓存，HTML 字符串逐帧不变 → Vue 不动这些 DOM；
     *   - 最后一块每帧重算，只有它在变。
     *
     * @param {string} text 流式累计的 markdown 文本
     * @returns {Array<{html: string, active: boolean}>}
     */
    function streamBlocks(text) {
        if (!text) return [];

        var memo = _blocksMemo.get(text);
        if (memo !== undefined) return memo;

        var segments = splitTopLevelBlocks(text);
        var tail = segments.length - 1;
        var blocks = new Array(segments.length);
        for (var i = 0; i < segments.length; i++) {
            blocks[i] = {
                html: i === tail ? renderHtml(segments[i]) : render(segments[i]),
                active: i === tail
            };
        }

        if (_blocksMemo.size >= BLOCKS_MEMO_LIMIT) {
            var oldest = _blocksMemo.keys().next();
            if (!oldest.done) _blocksMemo.delete(oldest.value);
        }
        _blocksMemo.set(text, blocks);
        return blocks;
    }

    /* ---- Mermaid：流式增量渲染 ---- */
    /*
     * 核心思路：
     *   1. render() / streamBlocks() 仍是同步的：命中缓存就返回 SVG，否则返回源码占位并
     *      登记一次异步渲染（去抖 + 前缀去重），不会阻塞 Vue。
     *   2. 渲染成功写入 code -> SVG 缓存，让订阅者（消息组件）强制重渲染，把占位换成 SVG。
     *      因为 v-html 只在字符串变化时才写 DOM，缓存命中后同一块不再被反复赋值，
     *      SVG 节点得以保留 —— 既不会每 chunk 重建 DOM，也能边输出边出图。
     *   3. 流式中当前 code 还没渲染好时，先用「最长的已成功前缀 code」的 SVG 顶着，
     *      避免图表在源码与图之间来回闪。
     */

    var MERMAID_DEBOUNCE_MS = 150;
    var MERMAID_RECENT_LIMIT = 24;
    var MERMAID_FAILED_LIMIT = 64;

    var _mermaidSeq = 0;
    var _mermaidInited = false;
    var _mermaidSvgCache = new Map();      // code -> svg
    var _mermaidRecentCodes = [];          // 最近成功渲染的 code（用于最长前缀回退）
    var _mermaidWanted = new Set();        // 待渲染的 code
    var _mermaidFailed = new Set();        // 渲染失败、不再重试的 code
    var _mermaidInFlight = new Map();      // code -> Promise<svg|null>
    var _mermaidQueue = Promise.resolve(); // mermaid.render 串行队列
    var _mermaidPumpTimer = null;
    var _mermaidSubs = [];                 // 渲染成功后的通知回调

    /** 首次渲染前惰性初始化 mermaid（lib 未加载时静默跳过） */
    function ensureMermaid() {
        if (_mermaidInited || typeof mermaid === 'undefined') return;
        try {
            mermaid.initialize({
                startOnLoad: false,
                theme: 'default',
                securityLevel: 'loose',
                suppressErrorRendering: true
            });
            _mermaidInited = true;
        } catch (e) {
            // 忽略初始化异常
        }
    }

    /** 精确缓存未命中时，找「最长的已成功前缀」对应的 SVG（流式过程中的旧版本） */
    function lookupMermaidPrefixSvg(code) {
        var best = null;
        var bestLen = 0;
        for (var i = _mermaidRecentCodes.length - 1; i >= 0; i--) {
            var c = _mermaidRecentCodes[i];
            if (c.length > bestLen && c.length < code.length && code.indexOf(c) === 0) {
                best = c;
                bestLen = c.length;
            }
        }
        return best ? _mermaidSvgCache.get(best) : null;
    }

    /** 最终调用 mermaid.render（串行，避免并发互相干扰）；成功即入缓存并通知订阅者 */
    function mermaidRenderSvg(code) {
        if (_mermaidSvgCache.has(code)) {
            return Promise.resolve(_mermaidSvgCache.get(code));
        }
        if (_mermaidInFlight.has(code)) {
            return _mermaidInFlight.get(code);
        }
        ensureMermaid();
        if (typeof mermaid === 'undefined') {
            return Promise.resolve(null);
        }

        var p = _mermaidQueue.then(function () {
            var id = 'mermaid-' + (++_mermaidSeq) + '-' + Date.now();
            return mermaid.render(id, code)
                .then(function (result) {
                    return result && result.svg ? result.svg : null;
                })
                .catch(function () {
                    return null;
                });
        });
        // 队列不能被 rejected 卡死
        _mermaidQueue = p.catch(function () {});
        _mermaidInFlight.set(code, p);

        p.then(function (svg) {
            _mermaidInFlight.delete(code);
            if (svg) {
                _mermaidSvgCache.set(code, svg);
                _mermaidRecentCodes.push(code);
                while (_mermaidRecentCodes.length > MERMAID_RECENT_LIMIT) {
                    _mermaidSvgCache.delete(_mermaidRecentCodes.shift());
                }
                if (_mermaidSubs.length) {
                    invalidateCachedMermaid(code);
                    emitMermaidRendered();
                }
            } else {
                _mermaidFailed.add(code);
                while (_mermaidFailed.size > MERMAID_FAILED_LIMIT) {
                    var oldest = _mermaidFailed.values().next();
                    if (oldest.done) break;
                    _mermaidFailed.delete(oldest.value);
                }
            }
        });
        return p;
    }

    /** 登记一次去抖渲染；已在等待/在途/已缓存/已失败则跳过，且不重置等待计时 */
    function scheduleMermaidRender(code) {
        if (typeof mermaid === 'undefined') return;
        if (_mermaidSvgCache.has(code) || _mermaidFailed.has(code) || _mermaidInFlight.has(code)) return;
        _mermaidWanted.add(code);
        if (_mermaidPumpTimer !== null) return;
        _mermaidPumpTimer = setTimeout(pumpMermaid, MERMAID_DEBOUNCE_MS);
    }

    /** 去抖到点：同一流式块产生的「旧 code 为新 code 前缀」只保留最长的那个 */
    function pumpMermaid() {
        _mermaidPumpTimer = null;
        if (typeof mermaid === 'undefined') {
            _mermaidWanted.clear();
            return;
        }
        var codes = Array.from(_mermaidWanted);
        _mermaidWanted.clear();
        codes = codes.filter(function (c) {
            return !codes.some(function (o) {
                return o !== c && o.length > c.length && o.indexOf(c) === 0;
            });
        });
        codes.forEach(function (c) {
            mermaidRenderSvg(c);
        });
    }

    /** 同步输出 mermaid 块的 HTML：优先精确 SVG，其次上一个可渲染版本，最后源码占位 */
    function renderMermaidBlock(code) {
        var svg = _mermaidSvgCache.get(code);
        if (!svg) {
            scheduleMermaidRender(code);
            svg = lookupMermaidPrefixSvg(code);
        }
        if (svg) {
            return '<div class="mermaid-wrapper">' + svg + '</div>';
        }
        return '<div class="mermaid-wrapper"><div class="mermaid">' + code + '</div></div>';
    }

    /**
     * 把容器内所有未处理的 .mermaid 占位渲染成 SVG。
     * <p>render() / streamBlocks() 已能自动增量渲染；此方法保留给直接插入
     * .mermaid 占位的场景（聊天外的插件页）以及回合结束时的兜底扫描。
     *
     * @param {Element|Document} [container=document] 限定查找范围
     * @returns {Promise<void>} 全部渲染结束
     */
    function renderMermaid(container) {
        ensureMermaid();
        if (typeof mermaid === 'undefined') return Promise.resolve();

        var root = container || document;
        var nodes = Array.prototype.slice.call(
            root.querySelectorAll('.mermaid:not([data-processed])')
        );
        if (nodes.length === 0) return Promise.resolve();

        var jobs = nodes.map(function (node) {
            var code = node.textContent || '';
            // 先标记，避免同一节点被重复渲染
            node.setAttribute('data-processed', 'true');
            return mermaidRenderSvg(code).then(function (svg) {
                if (svg) {
                    node.innerHTML = svg;
                }
            });
        });
        return Promise.all(jobs).then(function () {});
    }

    /** 缓存里含该 mermaid 源码占位的整段 HTML 失效，订阅者重渲染时会看到 SVG */
    function invalidateCachedMermaid(code) {
        var needle = '<div class="mermaid">' + code + '</div>';
        var doomed = [];
        _cache.forEach(function (html, key) {
            if (html.indexOf(needle) !== -1) {
                doomed.push(key);
            }
        });
        for (var i = 0; i < doomed.length; i++) {
            _cache.delete(doomed[i]);
        }
        _blocksMemo = new Map();
    }

    /** 订阅「mermaid 渲染完成」（返回取消订阅函数） */
    function onMermaidRendered(cb) {
        if (typeof cb !== 'function') return function () {};
        _mermaidSubs.push(cb);
        return function () {
            var idx = _mermaidSubs.indexOf(cb);
            if (idx !== -1) _mermaidSubs.splice(idx, 1);
        };
    }

    function emitMermaidRendered() {
        for (var i = 0; i < _mermaidSubs.length; i++) {
            try {
                _mermaidSubs[i]();
            } catch (e) {
                // 单个订阅者异常不影响其他订阅者
            }
        }
    }

    /* ---- JSON 参数美化：成功则格式化缩进，失败（流式半截 JSON/纯文本参数）原样返回 ---- */
    function beautify(text) {
        if (!text) return text;
        try {
            return JSON.stringify(JSON.parse(text), null, 2);
        } catch (e) {
            return text;
        }
    }

    return {
        render: render,
        streamBlocks: streamBlocks,
        renderMermaid: renderMermaid,
        onMermaidRendered: onMermaidRendered,
        clearCache: clearCache,
        beautify: beautify
    };
})();
