/**
 * lucide 安全渲染补丁
 *
 * 原生 lucide.createIcons() 会用 replaceChild 把 <i data-lucide> 整个替换成 <svg>，
 * 于是 Vue 手里的 vnode.el 变成已脱离文档的游离节点。一旦这些图标所在的
 * v-if / v-else / v-show 分支发生切换，或列表重排，Vue 就会对 null 调用
 * insertBefore / nextSibling，抛出难以定位的运行时错误。
 *
 * 本补丁在保留 lucide 全部能力的前提下，改为「在宿主元素内部渲染 svg」：
 * 宿主 <i> 始终留在 DOM 中，因此 Vue 的 vnode.el 始终有效；图标属性
 * （class / style / width / height 等）照旧搬到 svg 上，视觉与原生替换一致。
 *
 * 为了不丢属性：首次渲染时把宿主上的属性记到 __lucideAttrs 作为基线，
 * 之后 Vue 每次 patch 动态属性（:class / :style / :data-lucide 等）都会重新
 * 写到宿主上，渲染时与基线合并；静态 class/style 即使图标名变了也不会丢。
 *
 * 必须在 lib/lucide.min.js 之后、任何组件脚本之前引入。
 */
(function () {
    if (typeof lucide === 'undefined' || !lucide.icons || typeof lucide.createElement !== 'function') {
        return;
    }

    // 保留的宿主 <i> 只作 Vue 的 DOM 锚点，不应参与布局：否则它会引入行内行盒/
    // 基线对齐，导致图标被抬高或变高。display:contents 让内部 <svg> 像原生替换时
    // 一样，直接作为父容器的子项参与布局。
    if (!document.getElementById('lucide-safe-style')) {
        var styleEl = document.createElement('style');
        styleEl.id = 'lucide-safe-style';
        styleEl.textContent = 'i[data-lucide]{display:contents}';
        (document.head || document.documentElement).appendChild(styleEl);
    }

    var RENDERED_ATTR = 'data-lucide-rendered';

    /** kebab-case -> PascalCase，与 lucide 内部的名称归一化保持一致 */
    function toPascalCase(name) {
        var camel = name.replace(/^([A-Z])|[\s-_]+(\w)/g, function (match, upper, word) {
            return word ? word.toUpperCase() : upper.toLowerCase();
        });
        return camel.charAt(0).toUpperCase() + camel.slice(1);
    }

    function resolveIcon(name) {
        return lucide.icons[toPascalCase(name)] || lucide.icons[name] || null;
    }

    function renderInto(host) {
        var name = host.getAttribute('data-lucide');
        if (!name) {
            return;
        }

        // 收集宿主上除 data-lucide 外的属性：首次是模板里的静态属性 + 当时的动态属性；
        // 之后每次 Vue patch 动态属性都会再次写到宿主上。
        var current = {};
        var currentNames = [];
        for (var i = 0; i < host.attributes.length; i++) {
            var attr = host.attributes[i];
            if (attr.name === 'data-lucide' || attr.name === RENDERED_ATTR) {
                continue;
            }
            current[attr.name] = attr.value;
            currentNames.push(attr.name);
        }

        var isFirstRender = !host.__lucideAttrs;
        if (isFirstRender) {
            host.__lucideAttrs = current;
        } else if (currentNames.length > 0) {
            // 合并 Vue 本次写入的动态属性，动态值覆盖基线
            for (var key in current) {
                host.__lucideAttrs[key] = current[key];
            }
        }

        // 图标名未变、宿主也没有被 Vue 重新写属性，则无需重绘
        if (!isFirstRender
                && host.getAttribute(RENDERED_ATTR) === name
                && currentNames.length === 0
                && host.firstElementChild) {
            return;
        }

        var iconNode = resolveIcon(name);
        if (!iconNode) {
            return;
        }

        // 属性交给 createElement，得到与原生 replaceChild 时一致的 svg
        var svg = lucide.createElement(iconNode, host.__lucideAttrs);

        // 属性已转移到 svg，从宿主移除，避免同一份样式在宿主与 svg 上重复生效
        for (var j = 0; j < currentNames.length; j++) {
            host.removeAttribute(currentNames[j]);
        }

        while (host.firstChild) {
            host.removeChild(host.firstChild);
        }
        host.appendChild(svg);
        host.setAttribute(RENDERED_ATTR, name);
    }

    lucide.createIcons = function (options) {
        var root = (options && options.root) || document;
        var nodes = root.querySelectorAll ? root.querySelectorAll('[data-lucide]') : [];
        for (var i = 0; i < nodes.length; i++) {
            renderInto(nodes[i]);
        }
        return nodes.length;
    };
})();
