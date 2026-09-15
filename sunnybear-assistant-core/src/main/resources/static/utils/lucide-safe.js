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
 * ⚠ 例外一：display 必须留在宿主上，绝不复制到 svg。
 *  v-show 直接操作宿主 <i> 的 el.style.display，若把它一起搬到 svg，
 *  就会出现「宿主 / svg 双写」的两个后果——
 *   1) svg 上那份 display 会随基线被冻结，此后 v-show 只改宿主、再也改不到
 *      svg，表现为「切到显示态时图标依旧看不见」（点一下图标反而消失）；
 *   2) 宿主被摘掉 display 后不再隐藏，会以「隐藏图标」的身份继续占位，
 *      把同一个按钮里真正要显示的图标挤出中线（图标看着歪了）。
 *  所以：display 声明留在宿主，svg 只继承其余声明（尺寸、颜色等）。
 *
 * ⚠ 例外二：宿主需要一份「等大容器」的基础样式（见 injectBaseStyles）。
 *  svg 在宿主内部是行内盒、坐在文字基线上，宿主盒子会比图标本身高出
 *  字体下沉部那一截（实测 21px 里装 18px 的图标），于是图标在按钮、文字行里
 *  总是偏高 1.5px 左右。基础样式把宿主变成「和图标等大」的行内 flex 容器，
 *  居中由宿主自己负责；实测按钮与文字行的中心差全部归零。
 *  这里故意不写 vertical-align：行内文字里的图标依旧「底边贴基线」，
 *  与打补丁之前完全一致，避免把一个方向的偏差换成反方向的偏差。
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
    var BASE_STYLE_ID = 'lucide-safe-base-style';

    /**
     * 注入宿主基础样式（只注入一次）。
     * 选择器权重是最低的一档（单个属性选择器），组件自己的 .xxx i 规则
     * （权重更高）仍然可以覆盖它；v-show 写在宿主上的行内 display 更是
     * 永远优先，所以这里不会影响 v-show 的显隐。
     */
    function injectBaseStyles() {
        if (document.getElementById(BASE_STYLE_ID)) {
            return;
        }
        var style = document.createElement('style');
        style.id = BASE_STYLE_ID;
        style.textContent =
            '/* lucide 图标宿主：与图标等大的容器，居中由宿主负责，避免 svg 坐在基线上把图标顶高 */\n' +
            '[data-lucide]{display:inline-flex;align-items:center;justify-content:center;}\n' +
            '[data-lucide]>svg{display:block;flex:none;}\n';
        (document.head || document.documentElement).appendChild(style);
    }

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

    /**
     * 拆分 style 字符串：
     *  - display：交给 v-show，留在宿主 <i> 上；
     *  - rest：尺寸、颜色等，搬给 svg。
     */
    function splitStyle(styleValue) {
        var display = '';
        var rest = [];
        var decls = String(styleValue || '').split(';');
        for (var i = 0; i < decls.length; i++) {
            var decl = decls[i].replace(/^\s+|\s+$/g, '');
            if (!decl) {
                continue;
            }
            if (/^display\s*:/i.test(decl)) {
                display = display ? display + '; ' + decl : decl;
            } else {
                rest.push(decl);
            }
        }
        return { display: display, rest: rest.join('; ') };
    }

    /** 属性签名 = 图标名 + 排序后的属性键值，用来判断这次是否真的需要重绘 */
    function buildSignature(name, attrs) {
        var keys = [];
        for (var key in attrs) {
            keys.push(key);
        }
        keys.sort();
        var parts = [name];
        for (var i = 0; i < keys.length; i++) {
            parts.push(keys[i] + '=' + attrs[keys[i]]);
        }
        return parts.join('\n');
    }

    function renderInto(host) {
        var name = host.getAttribute('data-lucide');
        if (!name) {
            return;
        }

        // 收集宿主属性：display 去掉后剩下的部分搬给 svg
        var attrsForSvg = {};
        var moveOut = [];          // 需要从宿主上摘掉的属性名
        var hostDisplay = '';      // 留在宿主上的 display 声明（v-show 管辖）
        var staleOnHost = false;   // 宿主上又出现了 Vue 写入的动态属性

        for (var i = 0; i < host.attributes.length; i++) {
            var attr = host.attributes[i];
            if (attr.name === 'data-lucide' || attr.name === RENDERED_ATTR) {
                continue;
            }
            if (attr.name === 'style') {
                var split = splitStyle(attr.value);
                hostDisplay = split.display;
                attrsForSvg.style = split.rest;
                moveOut.push('style');
            } else {
                attrsForSvg[attr.name] = attr.value;
                moveOut.push(attr.name);
                staleOnHost = true;
            }
        }

        var isFirstRender = !host.__lucideAttrs;
        if (isFirstRender) {
            host.__lucideAttrs = attrsForSvg;
        } else {
            for (var key in attrsForSvg) {
                // 宿主上的 style 只剩 display，拆出来若为空，说明本次没有新的
                // 尺寸/样式信息，不能把基线上已有的 style 覆盖成空。
                if (key === 'style' && !attrsForSvg[key] && host.__lucideAttrs.style) {
                    continue;
                }
                host.__lucideAttrs[key] = attrsForSvg[key];
            }
        }

        var signature = buildSignature(name, host.__lucideAttrs);
        var svg = host.firstElementChild;

        // 图标名与属性签名都没变、宿主上也没有待搬走的残留 —— 收工
        if (!isFirstRender
                && svg
                && !staleOnHost
                && host.getAttribute(RENDERED_ATTR) === signature) {
            return;
        }

        var iconNode = resolveIcon(name);
        if (!iconNode) {
            return;
        }

        // 属性已转移到 svg，从宿主移除；只把 display 留在宿主上，
        // 这样 v-show 切宿主即可带动整个图标，也不会与 svg 上的样式重复生效。
        for (var j = 0; j < moveOut.length; j++) {
            host.removeAttribute(moveOut[j]);
        }
        if (hostDisplay) {
            host.setAttribute('style', hostDisplay);
        } else {
            host.removeAttribute('style');
        }

        // 只有签名真的变了才重建 svg：Vue 重复写入同值属性时只需清掉宿主上的副本
        if (!svg || host.getAttribute(RENDERED_ATTR) !== signature) {
            svg = lucide.createElement(iconNode, host.__lucideAttrs);
            while (host.firstChild) {
                host.removeChild(host.firstChild);
            }
            host.appendChild(svg);
        }
        host.setAttribute(RENDERED_ATTR, signature);
    }

    injectBaseStyles();

    lucide.createIcons = function (options) {
        var root = (options && options.root) || document;
        var nodes = root.querySelectorAll ? root.querySelectorAll('[data-lucide]') : [];
        for (var i = 0; i < nodes.length; i++) {
            renderInto(nodes[i]);
        }
        return nodes.length;
    };
})();
