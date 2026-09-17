/**
 * Mermaid 图表工具（兼容壳）
 *
 * 实际渲染逻辑已合并到 utils/render-markdown.js 的 MarkdownUtils.renderMermaid：
 *   - 初始化配置、占位查找、mermaid.render 生成 SVG 都在那边；
 *   - 这里只保留旧的 MermaidUtils.renderAll() 入口，避免改动既有调用点。
 *
 * 依赖（全局）：render-markdown.js（必须先加载）、mermaid
 */
const MermaidUtils = (function () {

    /**
     * 渲染页面中所有未处理的 .mermaid 元素
     *
     * @param {Element|Document} [container=document] 限定查找范围
     * @returns {Promise<void>} 全部渲染结束
     */
    function renderAll(container) {
        return MarkdownUtils.renderMermaid(container);
    }

    return { renderAll: renderAll };
})();
