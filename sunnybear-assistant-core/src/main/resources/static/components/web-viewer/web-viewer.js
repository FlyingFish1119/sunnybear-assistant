/**
 * 网页查看器（右侧滑出抽屉）
 *
 * 把外部网页 / 一段 HTML 代码用 iframe 从屏幕右侧滑出显示，而不是跳转离开当前页。
 * 与 image-viewer 同一套路：全局单例，首次调用才创建实例并挂到 body 上。
 *
 * ---------- 用法 ----------
 *   SbWebViewer.openUrl(url)            // 外部网页：先滑出抽屉，后端探测不让嵌则切说明面板
 *   SbWebViewer.openHtml(html, title)   // 一段 HTML 源码：走 srcdoc + sandbox 预览
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

                <footer class="sb-wv-foot">
                    <span class="sb-wv-foot-url" :title="displayUrl">{{ displayUrl }}</span>
                    <a class="sb-wv-foot-btn" :href="browserHref" target="_blank" rel="noopener noreferrer">
                        <i data-lucide="external-link"></i>
                        <span>用浏览器打开</span>
                    </a>
                </footer>
            </aside>
        </div>
    </transition>`,

    props: {
        mainColor: { type: String, default: '' }
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
            blobUrl: ''         // html 模式「用浏览器打开」用的 blob 地址
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
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        openUrl(url) {
            if (!url) return;
            this.mode = 'url';
            this.url = url;
            this.srcdoc = '';
            this.title = this.hostOf(url);
            this.blocked = false;
            this.blockedReason = '';
            this.visible = true;
            this.beginLoad();
            this.refreshIcons();
        },

        openHtml(html, title) {
            this.mode = 'html';
            this.srcdoc = html == null ? '' : String(html);
            this.url = '';
            this.title = title || 'HTML 预览';
            this.loading = false;
            this.slow = false;
            this.blocked = false;
            this.blockedReason = '';
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
            this._resetBlobUrl();
            this.clearTimers();
        },

        reload() {
            if (this.mode !== 'url') return;
            this.beginLoad();
            this.frameKey++;    // 换 key 强制 iframe 重建，等价于刷新
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

    window.SbWebViewer = {
        openUrl: openUrl,
        /** 一段 HTML 源码：一定可嵌（走 srcdoc），不开后端探测 */
        openHtml: function (html, title) {
            ensure().openHtml(html, title);
        },
        close: function () {
            if (instance) instance.close();
        }
    };

    /* markdown 正文里的外部链接：v-html 塞进来的，没法逐个绑，document 上兜一层。
       只认 http(s) 且带修饰键时放行给浏览器原生行为（用户想自己开新窗口）。 */
    document.addEventListener('click', function (e) {
        var el = e.target;
        if (!el || !el.closest) return;
        var a = el.closest('a[href]');
        if (!a || a.closest('.sb-wv')) return;
        if (!a.closest('.markdown-body')) return;
        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        var href = a.getAttribute('href') || '';
        if (!isHttpUrl(href)) return;
        e.preventDefault();
        window.SbWebViewer.openUrl(a.href);
    });
})();
