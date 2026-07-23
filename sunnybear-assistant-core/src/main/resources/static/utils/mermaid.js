/**
 * Mermaid 图表工具
 *
 * 依赖（全局）：mermaid
 */
const MermaidUtils = (function () {

    // 初始化配置
    mermaid.initialize({
        startOnLoad: false,
        theme: 'default',
        securityLevel: 'loose',
        suppressErrorRendering: true
    });

    /**
     * 渲染页面中所有未处理的 .mermaid 元素
     * 调用方在消息加载 / 流式结束后调用
     */
    function renderAll() {
        if (typeof mermaid !== 'undefined') {
            try {
                mermaid.run({ querySelector: '.mermaid' });
            } catch (e) {
                // mermaid 解析失败时静默忽略
            }
        }
    }

    return { renderAll: renderAll };
})();
