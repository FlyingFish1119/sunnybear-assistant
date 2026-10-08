/**
 * 网页查看器（右侧滑出抽屉）
 *
 * 把外部网页 / 一段 HTML 代码用 iframe 从屏幕右侧滑出显示，而不是跳转离开当前页。
 * 与 image-viewer 同一套路：全局单例，首次调用才创建实例并挂到 body 上。
 *
 * ---------- 用法 ----------
 *   SbWebViewer.openUrl(url)            // 外部网页：先滑出抽屉，后端探测不让嵌则切说明面板
 *   SbWebViewer.openHtml(html, title)   // 一段 HTML 源码：走 srcdoc + sandbox 预览
 *   SbWebViewer.openLocalFile(path)     // 本机文件：能预览就预览，否则系统默认程序打开
 *   SbWebViewer.close()
 *
 * ---------- 为什么要问后端 ----------
 * browser 允许不允许 iframe 嵌一个站，取决于它的 X-Frame-Options / CSP frame-ancestors
 * 响应头，而跨域下前端读不到这些头。后端 /web/frameable 探测后给结论。不让嵌时切到说明
 * 面板，由用户点「用浏览器打开」唤起系统浏览器。
 *
 * ---------- 为什么不让程序自动新标签页打开 ----------
 * window.open 只有在「用户手势同步触发」时才被放行。frameable 是异步探测，等它回来再
 * window.open 手势已经丢了，要么被弹窗拦截、要么在 PWA 里报
 * "Not allowed to launch 'microsoft-edge:...'"。所以自动打开这条路走不通，只能交给用户点。
 *
 * ---------- 接入点 ----------
 *   1) markdown 正文里的外部链接：本文件末尾的 document 级事件委托；
 *   2) HTML 代码块右上角的「在侧边栏打开」按钮：见 utils/render-markdown.js。
 *
 * ---------- 安全说明 ----------
 *   · HTML 预览用 sandbox，且故意不给 allow-same-origin —— 脚本能跑但拿到的是不透明源，
 *     碰不到本页的 cookie / localStorage / DOM。
 *   · 外部网页不加 sandbox（会破坏大量站点），嵌入与否交给目标站自己的响应头决定。
 */

const WebViewer = {
    name: 'WebViewer',

    template: `
    <transition name="sb-wv-slide">
        <div v-if="visible" class="sb-wv" :style="{ '--main-color': mainColor }">
            <div class="sb-wv-mask" @click="close"></div>
            <aside class="sb-wv-drawer" role="dialog" aria-label="网页视图">
                <header class="sb-wv-head">
                    <i data-lucide="globe" class="sb-wv-head-icon"></i>
                    <div class="sb-wv-title" :title="displayUrl">
                        <span class="sb-wv-title-text">{{ title || displayUrl }}</span>
                        <span v-if="hostText" class="sb-wv-host">{{ hostText }}</span>
                    </div>
                    <button v-if="mode === 'url'" class="sb-wv-btn" @click="reload" title="重新加载">
                        <i data-lucide="refresh-cw"></i>
                    </button>
                    <!-- 本机文件专用：交给系统默认程序打开原文件 -->
                    <button v-if="localPath" class="sb-wv-btn" @click="openViaSystem" title="用系统默认程序打开">
                        <i data-lucide="app-window"></i>
                    </button>
                    <!-- 用真正的 <a target="_blank"> 而不是 window.open：PWA 里只有原生
                         锚点导航才会被交给系统浏览器，window.open 会被拦 -->
                    <a class="sb-wv-btn" :href="browserHref" target="_blank" rel="noopener noreferrer"
                       title="用浏览器打开">
                        <i data-lucide="external-link"></i>
                    </a>
                    <button class="sb-wv-btn" @click="close" title="关闭（Esc）">
                        <i data-lucide="x"></i>
                    </button>
                </header>

                <div class="sb-wv-body">
                    <!-- 不可内嵌：说明原因 + 浏览器打开入口。这里是按钮直接手势，
                         window.open 不会被弹窗拦截器拦下（自动回退在异步回调里常被拦） -->
                    <div v-if="blocked" class="sb-wv-notice">
                        <i data-lucide="panel-right-close" class="sb-wv-notice-icon"></i>
                        <p class="sb-wv-notice-title">该网页不支持内嵌</p>
                        <p class="sb-wv-notice-reason">{{ blockedReason }}</p>
                        <a class="sb-wv-notice-btn" :href="browserHref" target="_blank" rel="noopener noreferrer">
                            <i data-lucide="external-link"></i>
                            <span>用浏览器打开</span>
                        </a>
                    </div>

                    <template v-else>
                        <iframe v-if="mode === 'url'"
                                :key="frameKey"
                                class="sb-wv-frame"
                                :src="url"
                                referrerpolicy="no-referrer"
                                @load="onFrameLoad"></iframe>
                        <iframe v-else
                                class="sb-wv-frame"
                                :srcdoc="srcdoc"
                                sandbox="allow-scripts allow-forms allow-modals allow-popups allow-popups-to-escape-sandbox"></iframe>

                        <div v-if="mode === 'url' && loading" class="sb-wv-status">
                            <i data-lucide="loader-circle" class="sb-wv-spin"></i>
                            <span>{{ slow ? '加载较慢…可用浏览器打开' : '加载中…' }}</span>
                        </div>
                    </template>
                </div>
            </aside>
        </div>
    </transition>`,

    inject: {
        appSettings: { default: null }
    },

    data() {
        return {
            visible: false,
            mode: 'url',        // 'url' | 'html'
            url: '',
            srcdoc: '',
            title: '',
            loading: false,
            slow: false,
            frameKey: 0,
            blocked: false,     // 目标不允许内嵌（此时不加载 iframe，只给浏览器打开入口）
            blockedReason: '',
            blobUrl: '',        // html 模式「用浏览器打开」用的 blob 地址
            localPath: ''       // 非空表示当前是本机文件预览，顶部显示「用系统程序打开」
        };
    },

    computed: {
        displayUrl() {
            if (this.mode === 'html') return '内嵌 HTML 预览';
            return this.url;
        },
        /** 「用浏览器打开」的 href：url 模式是原地址，html 模式是 blob 地址 */
        browserHref() {
            return this.mode === 'html' ? this.blobUrl : this.url;
        },
        hostText() {
            if (this.mode !== 'url') return '';
            try {
                return new URL(this.url).host;
            } catch (e) {
                return '';
            }
        },

        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return '';
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        openUrl(url, localPath) {
            if (!url) return;
            this.mode = 'url';
            this.url = url;
            this.srcdoc = '';
            this.title = this.hostOf(url);
            this.blocked = false;
            this.blockedReason = '';
            this.localPath = localPath || '';
            this.visible = true;
            this.beginLoad();
            this.refreshIcons();
        },

        openHtml(html, title, localPath) {
            this.mode = 'html';
            this.srcdoc = html == null ? '' : String(html);
            this.url = '';
            this.title = title || 'HTML 预览';
            this.loading = false;
            this.slow = false;
            this.blocked = false;
            this.blockedReason = '';
            this.localPath = localPath || '';
            this._resetBlobUrl();
            this.blobUrl = URL.createObjectURL(new Blob([this.srcdoc], { type: 'text/html' }));
            this.visible = true;
            this.refreshIcons();
        },

        /** 目标不允许内嵌时切到这个面板：说明原因 + 浏览器打开入口 */
        openBlocked(url, reason) {
            this.mode = 'url';
            this.url = url;
            this.srcdoc = '';
            this.title = this.hostOf(url);
            this.blocked = true;
            this.blockedReason = reason || '目标站点不允许被嵌入';
            this.loading = false;
            this.slow = false;
            this.localPath = '';
            this.visible = true;
            this.clearTimers();
            this.refreshIcons();
        },

        close() {
            this.visible = false;
            this.url = '';
            this.srcdoc = '';
            this.loading = false;
            this.slow = false;
            this.blocked = false;
            this.blockedReason = '';
            this.localPath = '';
            this._resetBlobUrl();
            this.clearTimers();
        },

        reload() {
            if (this.mode !== 'url') return;
            this.beginLoad();
            this.frameKey++;    // 换 key 强制 iframe 重建，等价于刷新
        },

        /** 交给后端用系统默认程序打开当前本机文件（原文件，而非预览副本） */
        openViaSystem() {
            if (!this.localPath || typeof API === 'undefined' || !API.localFile) return;
            API.localFile.open(this.localPath).then(function (res) {
                if (!window.SbToast) return;
                if (res && res.status === 200) {
                    window.SbToast.info('已用系统默认程序打开');
                } else {
                    window.SbToast.info('打开失败：' + ((res && res.message) || '未知错误'));
                }
            }).catch(function () {
                if (window.SbToast) window.SbToast.info('打开失败');
            });
        },

        /* ==================== 加载态 ==================== */

        beginLoad() {
            this.loading = true;
            this.slow = false;
            this.clearTimers();
            this._slowTimer = setTimeout(() => {
                if (this.visible && this.loading) this.slow = true;
            }, 6000);
        },

        onFrameLoad() {
            this.loading = false;
            this.slow = false;
            this.clearTimers();
        },

        clearTimers() {
            if (this._slowTimer) {
                clearTimeout(this._slowTimer);
                this._slowTimer = null;
            }
        },

        hostOf(url) {
            try {
                return new URL(url).host;
            } catch (e) {
                return url;
            }
        },

        /** 释放上一个 html 预览的 blob 地址，避免泄漏 */
        _resetBlobUrl() {
            if (this.blobUrl) {
                URL.revokeObjectURL(this.blobUrl);
                this.blobUrl = '';
            }
        },

        refreshIcons() {
            this.$nextTick(() => {
                if (typeof lucide !== 'undefined') lucide.createIcons();
            });
        },

        onKeydown(e) {
            if (!this.visible) return;
            if (e.key === 'Escape') {
                e.stopPropagation();
                this.close();
            }
        }
    },

    mounted() {
        document.addEventListener('keydown', this.onKeydown);
    },

    beforeUnmount() {
        document.removeEventListener('keydown', this.onKeydown);
        this.clearTimers();
        this._resetBlobUrl();
    },

    updated() {
        if (typeof lucide !== 'undefined') lucide.createIcons();
    }
};

/* ============================================================
   全局单例挂载 + markdown 正文链接的事件委托
   ============================================================ */
(function () {
    var instance = null;

    function ensure() {
        if (instance) return instance;
        var host = document.createElement('div');
        host.className = 'sb-wv-host';
        document.body.appendChild(host);
        instance = Vue.createApp(WebViewer).mount(host);
        return instance;
    }

    /** 是否是可直接交给 iframe / 浏览器的 http(s) 地址 */
    function isHttpUrl(url) {
        return /^https?:\/\//i.test(url || '');
    }

    /**
     * 兜底：剥掉 `microsoft-edge:https://...` 这类浏览器前缀协议。
     * 正常渲染已在 MarkdownUtils 那侧剥过一次（见 render-markdown.js 的 unwrapBrowserScheme），
     * 这里再兜一层，防止旧缓存 / 其它来源的 href 漏进来。
     */
    var BROWSER_SCHEME_RE = /^(?:microsoft-edge|msedge|microsoft-edge-webview|edge|chrome|googlechrome|chromium|firefox|brave|opera|vivaldi|safari):(https?:\/\/[\s\S]+)$/i;

    function unwrapBrowserScheme(url) {
        if (url == null) return url;
        var m = String(url).match(BROWSER_SCHEME_RE);
        return m ? m[1] : url;
    }

    /**
     * 外部网页入口。
     *
     * 关键点：window.open 只能「用户手势同步触发」才算数。frameable 是异步探测，等它回来
     * 再 window.open 会丢掉手势，浏览器要么拦截弹窗，要么（PWA 里）直接报
     * "Not allowed to launch 'microsoft-edge:...'" —— 点了跟没点一样。
     *
     * 所以这里反过来：先同步滑出抽屉（iframe 自己会去加载，立刻有反馈），同时后台探测；
     * 探测回来说「不让嵌」就把抽屉切到说明面板，面板里的「用浏览器打开」按钮是直接点击，
     * 一定能唤起。探测失败（后端异常等）就保持抽屉，让 iframe 自己试一把。
     */
    function openUrl(url) {
        if (!url || !isHttpUrl(url)) return;
        var view = ensure();
        view.openUrl(url);
        if (typeof API === 'undefined' || !API.web || !API.web.frameable) return;
        API.web.frameable(url).then(function (res) {
            var data = res && res.data;
            if (data && !data.frameable) {
                view.openBlocked(url, data.reason || '该网页不支持内嵌');
            }
        }).catch(function () {
            // 探测失败：保持 iframe，让它自己试
        });
    }

    /* ==================== 本机文件 ==================== */

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /**
     * file:// URL → 本机路径。
     *   file:///C:/a/b.html → C:/a/b.html（Windows 盘符）
     *   file:///home/u/a.md → /home/u/a.md（POSIX）
     *   file://server/share/a → //server/share/a（UNC）
     */
    function fileUrlToPath(url) {
        var m = /^file:\/\/([^\/]*)(\/.*)?$/i.exec(url || '');
        if (!m) return '';
        var host = m[1] || '';
        var p = m[2] || '';
        try { p = decodeURIComponent(p); } catch (e) {}
        if (host) return '//' + host + p;
        if (/^\/[A-Za-z]:/.test(p)) p = p.substring(1);
        return p;
    }

    function baseDoc(inner) {
        return '<!DOCTYPE html><html><head><meta charset="utf-8">'
            + '<style>'
            + 'html,body{margin:0;height:100%;}'
            + 'body{background:#fff;color:#24292e;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"PingFang SC","Microsoft YaHei",sans-serif;}'
            + 'pre{margin:0;padding:16px;white-space:pre-wrap;word-break:break-word;font:13px/1.6 "Cascadia Code","Fira Code",Consolas,monospace;}'
            + 'img{max-width:100%;height:auto;display:block;margin:0 auto;}'
            + '.center{display:flex;align-items:center;justify-content:center;height:100%;}'
            + 'video{max-width:100%;max-height:100%;}'
            + '</style></head><body>' + inner + '</body></html>';
    }

    /** 文本预览：转义后塞进 <pre>，保留空白 */
    function buildTextDoc(text) {
        return baseDoc('<pre>' + escapeHtml(text) + '</pre>');
    }

    /** 图片预览：走 /file/proxy 拿字节；<img> 不执行脚本，svg 也安全 */
    function buildImageDoc(proxyUrl) {
        return baseDoc('<div class="center"><img src="' + escapeHtml(proxyUrl) + '" alt="preview"></div>');
    }

    /** 音视频预览：按 MIME 决定 audio / video */
    function buildMediaDoc(proxyUrl, mime) {
        var tag = (mime || '').indexOf('video/') === 0 ? 'video' : 'audio';
        var attrs = tag === 'video' ? ' controls' : ' controls style="width:100%"';
        return baseDoc('<div class="center"><' + tag + ' src="' + escapeHtml(proxyUrl) + '"' + attrs + '></' + tag + '></div>');
    }

    function proxyUrlOf(path) {
        if (typeof API === 'undefined') return path;
        return API.fileProxyUrl(path);
    }

    function notify(message) {
        if (window.SbToast) window.SbToast.info(message);
    }

    /** 交给系统默认程序打开（后端执行），兜底路径 */
    function openLocalSystem(path, reason) {
        if (typeof API === 'undefined' || !API.localFile) {
            notify('无法打开本机文件');
            return;
        }
        API.localFile.open(path).then(function (res) {
            if (res && res.status === 200) {
                notify('已用系统默认程序打开');
            } else {
                notify((reason ? reason + '；' : '') + '打开失败：' + ((res && res.message) || '未知错误'));
            }
        }).catch(function () {
            notify('打开本机文件失败');
        });
    }

    /**
     * 本机文件入口：能预览就抽屉预览，否则交系统默认程序。
     * html/text 走 srcdoc 预览（沙箱，安全）；image/media/pdf 走 /file/proxy 拿字节；
     * 其余类型或预览失败 -> 系统打开。
     */
    function openLocalFile(path, forceSystem) {
        if (!path) return;
        if (forceSystem || typeof API === 'undefined' || !API.localFile) {
            openLocalSystem(path, null);
            return;
        }
        var view = ensure();
        API.localFile.preview(path).then(function (res) {
            var d = res && res.data;
            if (!d || res.status !== 200) {
                openLocalSystem(path, res && res.message);
                return;
            }
            switch (d.kind) {
                case 'html':
                    view.openHtml(d.text, d.name, path);
                    break;
                case 'text':
                    view.openHtml(buildTextDoc(d.text), d.name, path);
                    break;
                case 'image':
                    view.openHtml(buildImageDoc(proxyUrlOf(path)), d.name, path);
                    break;
                case 'media':
                    view.openHtml(buildMediaDoc(proxyUrlOf(path), d.mime), d.name, path);
                    break;
                case 'pdf':
                    view.openUrl(proxyUrlOf(path), path);
                    break;
                default:
                    openLocalSystem(path, null);
            }
        }).catch(function () {
            openLocalSystem(path, null);
        });
    }

    window.SbWebViewer = {
        openUrl: openUrl,
        /** 一段 HTML 源码：一定可嵌（走 srcdoc），不开后端探测 */
        openHtml: function (html, title) {
            ensure().openHtml(html, title);
        },
        /** 本机文件：预览 + 系统打开兜底 */
        openLocalFile: openLocalFile,
        close: function () {
            if (instance) instance.close();
        }
    };

    /* markdown 正文里的链接：v-html 塞进来的，没法逐个绑，document 上兜一层。
       http(s) 走网页查看器；file:// 走本机文件流程；带修饰键时放行给浏览器原生行为。 */
    document.addEventListener('click', function (e) {
        var el = e.target;
        if (!el || !el.closest) return;
        var a = el.closest('a[href]');
        if (!a || a.closest('.sb-wv')) return;
        if (!a.closest('.markdown-body')) return;
        if (e.button !== 0) return;
        var href = a.getAttribute('href') || '';

        // 本机文件：浏览器禁止 http 页面访问 file://，原生怎么点都打不开，一律自己接
        if (/^file:/i.test(href)) {
            e.preventDefault();
            var localPath = fileUrlToPath(href) || href.replace(/^file:/i, '');
            // 带修饰键 = 强制用系统默认程序打开（不进抽屉预览）
            var forceSystem = e.metaKey || e.ctrlKey || e.shiftKey || e.altKey;
            window.SbWebViewer.openLocalFile(localPath, forceSystem);
            return;
        }

        // http(s)：带修饰键放行给浏览器原生行为（用户想自己开新窗口）
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        var realHref = unwrapBrowserScheme(href);
        if (!isHttpUrl(realHref)) return;
        e.preventDefault();
        var absolute;
        try {
            absolute = new URL(realHref, window.location.href).href;
        } catch (err) {
            absolute = realHref;
        }
        window.SbWebViewer.openUrl(absolute);
    });
})();
