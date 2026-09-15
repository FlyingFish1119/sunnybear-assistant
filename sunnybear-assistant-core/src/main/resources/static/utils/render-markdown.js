/**
 * Markdown 渲染工具
 *
 * 将 markdown 文本转为 HTML，支持：
 *   - highlight.js 代码高亮（通过 marked 自定义渲染器）
 *   - Mermaid 图表（输出占位 div，调用方自行触发 mermaid.run）
 *   - KaTeX 数学公式（块级 $$...$$ / 行内 $...$）
 *
 * 依赖（全局）：marked, hljs, katex
 */
const MarkdownUtils = (function () {

    /* ---- 编辑工具 diff 渲染辅助 ---- */

    /** HTML 转义（diff 分支自行拼 HTML 用） */
    function escapeHtml(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
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
            code: function (obj) {
                var text = obj.text;
                var lang = obj.lang;
                if (lang === 'mermaid') {
                    return '<div class="mermaid-wrapper"><div class="mermaid">' + text + '</div></div>';
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

    /* ---- 缓存 ---- */
    var _cache = new Map();

    function clearCache() {
        _cache = new Map();
    }

    /* ---- 渲染 ---- */
    function render(text) {
        if (!text) return '';
        // 每次 Vue 重渲染可能对同一 content 重复调用，缓存避免无意义计算
        var cached = _cache.get(text);
        if (cached !== undefined) return cached;
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
            html = html.replace(/(src|href)="(\/?)(file\/proxy\?[^"]+)"/g,
                function (match, attr, slash, rest) {
                    return attr + '="' + API.BASE_PATH + rest + '"';
                });
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
            html = text;
        }
        _cache.set(text, html);
        return html;
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

    return { render: render, clearCache: clearCache, beautify: beautify };
})();
