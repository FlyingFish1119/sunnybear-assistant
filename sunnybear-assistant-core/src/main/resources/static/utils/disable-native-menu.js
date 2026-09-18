/**
 * 禁用浏览器原生右键菜单（主页面专用）
 *
 * 背景：应用要用自己的右键菜单替代系统那套，所以在 document 上兜底拦一层。
 * 侧边栏那几个会话项原本各写了一份 @contextmenu.prevent（chat-sidebar /
 * world-sidebar / character-sidebar），本脚本不替代它们 —— 元素级监听的执行
 * 顺序先于 document，两边各走各的，只是把没覆盖到的地方补齐。
 *
 * 三条判定，别删：
 *   1) input / textarea / contenteditable —— 右键在这几类元素上担着「粘贴」
 *      「拼写检查」「输入法候选」。一刀切会让发送框没法右键粘贴，Ctrl+V 虽然还在，
 *      但习惯右键的人会当场懵住，这是实打实的体验损失。
 *   2) data-native-menu 属性 —— 逃生口。哪天某个角落真需要系统菜单，
 *      给元素挂个属性就行，不用回来改这个文件。
 *   3) data-own-menu 属性 —— 自带菜单区。这类地方归它自己那份菜单管：原生菜单
 *      要拦（归我们管），但全局菜单得让路。侧边栏会话项就是这种 —— 它自己弹
 *      「删除会话 / 导出对话」那一套，全局菜单再挤进来就是两份菜单叠一块儿。
 *
 * 只 preventDefault，不 stopPropagation：
 *   自定义菜单组件（components/context-menu）在 document 上监听 contextmenu 照常
 *   收事件，按注册顺序执行，与本脚本互不干扰。
 *
 * 范围：只给 index.html 引。plug/world、plug/character 两个插件页自包含，
 * 维持原样，除非出现破坏性改动。
 */
(function () {
    /** 放行原生菜单的元素 */
    var PASS_SELECTOR = 'input, textarea, [contenteditable]:not([contenteditable="false"]), [data-native-menu]';

    /** 自带右键菜单的区域：原生菜单照样拦，但全局菜单要让路 */
    var OWN_MENU_SELECTOR = '[data-own-menu]';

    function matches(target, selector) {
        // 理论上 contextmenu 的 target 总是元素，但文本节点等情况也兜一下，
        // 免得 closest 调用抛错把整个右键路断掉
        if (!target || target.nodeType !== 1 || typeof target.closest !== 'function') {
            return false;
        }
        return !!target.closest(selector);
    }

    function shouldPass(target) {
        return matches(target, PASS_SELECTOR);
    }

    function hasOwnMenu(target) {
        return matches(target, OWN_MENU_SELECTOR);
    }

    /* 对外暴露判定：自定义右键菜单（components/context-menu）复用同一套规则 ——
       规则只此一份，免得「放行区 / 自有区」出现两套判断、迟早不同步 */
    window.NativeMenuGuard = {
        PASS_SELECTOR: PASS_SELECTOR,
        OWN_MENU_SELECTOR: OWN_MENU_SELECTOR,
        shouldPass: shouldPass,
        hasOwnMenu: hasOwnMenu
    };

    document.addEventListener('contextmenu', function (e) {
        if (shouldPass(e.target)) return;
        e.preventDefault();
    });
})();
