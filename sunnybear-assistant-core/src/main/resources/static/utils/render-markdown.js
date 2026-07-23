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

    /* ---- 配置 marked ---- */
    marked.use({
        renderer: {
            code: function (obj) {
                var text = obj.text;
                var lang = obj.lang;
            if (lang === 'mermaid') {
                return '<div class="mermaid-wrapper"><div class="mermaid">' + text + '</div></div>';
            }
            var langLabel = lang || '';
            var supported = lang && hljs.getLanguage(lang);
            var highlighted = supported
                ? hljs.highlight(text, { language: lang }).value
                : hljs.highlightAuto(text).value;
            if (langLabel) {
                return '<div class="code-block-wrapper">'
                    + '<div class="code-block-header"><span class="code-block-lang">' + langLabel + '</span></div>'
                    + '<pre><code class="hljs' + (supported ? ' language-' + lang : '') + '">'
                    + highlighted
                    + '</code></pre></div>';
            }
            return '<div class="code-block-wrapper">'
                + '<pre><code class="hljs">'
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

    return { render: render, clearCache: clearCache };
})();
