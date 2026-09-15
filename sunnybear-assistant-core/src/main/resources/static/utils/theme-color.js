// PWA / 主题色缓存：
// 1) 尽早把 localStorage 里缓存的主题色写到根元素 --main-color，避免加载阶段闪默认色；
// 2) 把 --main-color 同步到 <meta name="theme-color">，让状态栏 / PWA 窗口边框跟随主题色。
// 各页面在 data 初始化时可用 window.ThemeColorCache.get() 读取同一份缓存。
(function () {
    var KEY = 'assistant-mainColor';

    function readCache() {
        try {
            return localStorage.getItem(KEY);
        } catch (e) {
            return null;
        }
    }

    function writeCache(color) {
        if (!color) return;
        try {
            localStorage.setItem(KEY, color);
        } catch (e) {}
    }

    function current() {
        return getComputedStyle(document.documentElement)
            .getPropertyValue('--main-color')
            .trim();
    }

    function apply(color) {
        if (color) {
            document.documentElement.style.setProperty('--main-color', color);
        }
    }

    function themeMeta() {
        var meta = document.querySelector('meta[name="theme-color"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.setAttribute('name', 'theme-color');
            meta.setAttribute('content', 'lightsalmon');
            document.head.appendChild(meta);
        }
        return meta;
    }

    function sync() {
        var color = current();
        if (color) {
            themeMeta().setAttribute('content', color);
        }
    }

    // 加载阶段：优先用缓存主题色（请求返回后由页面 watcher 覆盖为服务端值）
    if (!current()) {
        apply(readCache());
    }

    function boot() {
        themeMeta();
        sync();
        new MutationObserver(sync).observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['style']
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }

    window.ThemeColorCache = { key: KEY, get: readCache, set: writeCache, apply: apply };
})();
