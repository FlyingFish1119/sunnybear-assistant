/**
 * Temp Chat —— 临时单次问答的小框
 *
 * 承接后端的 TempChatProcessor（无工具、无上下文的一次性问题），
 * 第一个落地场景是右键菜单里的「快速解释」（mode: temp_what_is_this）。
 *
 * ---------- 用法（全局单例，懒挂载） ----------
 *   TempChat.ask({ mode: 'temp_what_is_this', content: '选中的文字', anchor: rect })
 *   TempChat.close()
 *
 * ---------- 两条流式通路都得走得通 ----------
 * 后端 mission 现在是 stream:false —— 不会来 chunk，只有一个收尾帧，
 * text 里就是完整内容。将来谁把 stream 打开，就会先来一串 temp_chunk 增量。
 * 所以这里不去假设哪条路：
 *   · 收到 temp_chunk → 累加显示（有就是流式）
 *   · 收到 temp       → 用它的 text 覆盖（非流式时这就是全部内容；
 *                       流式时它是全量结果，顺手把累加过程中的任何偏差校准掉）
 * 两种模式共用同一段逻辑，前端不需要知道后端开没开流式。
 *
 * ---------- 定位 ----------
 * 三步，缺一步都会出问题：
 *   1) 贴锚点：选中文字右侧，右边放不下翻到左侧，再夹进视口（place）
 *   2) 内容变长后重收一次底边（reflow）—— 定位当时框里还是「思考中…」，
 *      高不过几十像素；等正文回来高度能翻好几倍，不重算的话底部直接跑出屏幕，
 *      用户就看着框"沉"下去了
 *   3) 用户拖过之后位置归他（_pinned），只做越界收敛，不再自动贴锚点
 *
 * ---------- 并发 ----------
 * 服务端按「连接 + 模式」做了在途登记，同一时刻只会有一个 temp 请求在跑，
 * 后到的会被拒并回一句提示。所以前端不需要给帧做归属判断 —— 框里收到的
 * temp 帧必然是这次请求的。前端这里只加一道体验层的锁：正在跑的时候再点
 * 「快速解释」不去打扰服务端，直接提示一下。
 *
 * ---------- 已知边界 ----------
 *   · 请求发出去后关掉框，响应到达会被丢弃（visible 为 false 直接 return），
 *     不做「后台跑完再弹回来」这种事 —— 用户既然关了就是不想要了。
 *   · 超时按 60s 兜底，避免后端卡住时框里永远转圈。
 *   · 长度护栏见 TEMP_CHAT_MAX_LENGTH，只挡「全选一整段糊过来」的手滑；
 *     长短该走解释还是概括，由后端按内容自己分流，前端不参与。
 */
/**
 * 选中文本的长度护栏。
 *
 * 注意这不是「功能边界」而是「手滑护栏」—— 长短该走解释还是概括，由后端按内容
 * 自己分流（TempChatProcessor.EXPLAIN_MAX_LENGTH），前端不掺和，也不该知道那条线画在哪。
 *
 * 这里只防一种情况：用户 Ctrl+A 把整段长会话全选上糊过去 —— 请求要等很久、
 * token 也烧得莫名其妙，那时候用户只会以为是卡死了。
 * 10000 字符足够装下一段话、一段代码、一个段落，正常用碰不到这条线。
 */
const TEMP_CHAT_MAX_LENGTH = 10000;

const TempChat = {
    name: 'TempChat',

    template: `
    <div v-if="visible"
         ref="panel"
         class="sb-tc"
         :class="{ 'is-ready': ready, 'is-dragging': dragging }"
         :style="posStyle"
         @contextmenu.prevent.stop>
        <div class="sb-tc-head"
             ref="head"
             :class="{ 'is-grabbing': dragging }"
             @pointerdown="onHeadDown"
             @pointermove="onHeadMove"
             @pointerup="onHeadUp"
             @pointercancel="onHeadUp">
            <i data-lucide="sparkles"></i>
            <span class="sb-tc-title">快速解释</span>
            <button class="sb-tc-close" @click="close" title="关闭（Esc）">
                <i data-lucide="x"></i>
            </button>
        </div>

        <div class="sb-tc-quote" :title="question">{{ question }}</div>

        <div class="sb-tc-body" ref="body">
            <div v-if="loading && !content" class="sb-tc-hint">
                <i data-lucide="loader-circle" class="sb-tc-spin"></i>
                <span>思考中…</span>
            </div>
            <div v-else-if="failed" class="sb-tc-hint is-error">
                <i data-lucide="circle-alert"></i>
                <span>{{ failedText }}</span>
            </div>
            <div v-else class="sb-tc-md markdown-body" v-html="html"></div>
        </div>
    </div>`,

    data() {
        return {
            visible: false,
            ready: false,
            x: 0,
            y: 0,

            mode: '',
            question: '',
            content: '',

            loading: false,
            failed: false,
            failedText: '',

            /** 正在被拖着走 */
            dragging: false,

            /** 定位锚点（选中文字的包围盒），可能为 null */
            _anchor: null,
            /** 用户亲手拖过：位置从此归他，不再自动贴锚点 */
            _pinned: false,
            /** 拖动过程中的指针状态 */
            _drag: null,
            _offFrame: null,
            _timer: null
        };
    },

    computed: {
        posStyle() {
            return { left: this.x + 'px', top: this.y + 'px' };
        },

        html() {
            if (!this.content) return '';
            if (typeof MarkdownUtils !== 'undefined') {
                return MarkdownUtils.render(this.content);
            }
            return this.content;
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        /** @param {{mode:string, content:string, anchor?:DOMRect}} options */
        ask(options) {
            var content = (options && options.content ? String(options.content) : '').trim();
            if (!content) return;

            // 手滑护栏：挡的是「Ctrl+A 全选一整段会话糊过来」这种，正常选中碰不到这条线。
            // 至于长短该走解释还是概括，那是后端按内容分流的事，这里不掺和。
            if (content.length > TEMP_CHAT_MAX_LENGTH) {
                if (window.SbToast) {
                    window.SbToast.warning('选中的内容太多了（' + content.length
                        + ' 字），最多支持 ' + TEMP_CHAT_MAX_LENGTH + ' 字');
                }
                return;
            }

            // 上一段还在跑就不打扰服务端了 —— 它那边也会拒，但没必要来回一趟
            if (this.loading) {
                if (window.SbToast) window.SbToast.warning('上一段还在解释中，稍等一下');
                return;
            }

            this.mode = options.mode || 'temp_what_is_this';
            this.question = content;
            this.content = '';
            this.failed = false;
            this.failedText = '';
            this.loading = true;
            this._anchor = options.anchor || null;
            // 新问题重新贴锚点，上次拖到的位置不带过来
            this._pinned = false;

            this.visible = true;
            this.ready = false;
            this.$nextTick(() => {
                this.place();
                this.ready = true;
                this.refreshIcons();
            });

            this.send();
        },

        close() {
            this.visible = false;
            this.ready = false;
            this.loading = false;
            this.dragging = false;
            this._drag = null;
            this.stopTimer();
            // 不清 content：万一只是误点关掉，重新打开还能看见上次结果（下次 ask 会清）
        },

        /* ==================== 发送 ==================== */

        send() {
            var ws = typeof WsBus !== 'undefined' ? WsBus.getSocket() : null;
            if (!ws || ws.readyState !== 1) {
                this.fail('连接已断开，无法发起请求');
                return;
            }
            try {
                ws.send(JSON.stringify({ mode: this.mode, content: this.question }));
            } catch (e) {
                this.fail('发送失败：' + e.message);
                return;
            }
            this.startTimer();
        },

        /* ==================== 收帧 ==================== */

        /**
         * temp 帧是 JSON 且没有信号名，只会走 WsBus 的 '*' 兜底 ——
         * 本组件订阅 '*'，在这里按 status 筛出自己关心的两帧。
         */
        onWsFrame(raw) {
            if (!this.visible) return;
            if (typeof raw !== 'string' || raw.startsWith(':') || raw.startsWith('###')) return;

            var resp;
            try {
                resp = JSON.parse(raw);
            } catch (e) {
                return;
            }
            if (!resp) return;

            // 流式增量：有就显示（后端开 stream 时才会来）
            if (resp.status === 'temp_chunk') {
                if (resp.text) {
                    this.content += resp.text;
                    this.$nextTick(() => {
                        this.scrollToBottom();
                        this.reflow();
                    });
                }
                return;
            }

            // 收尾帧：非流式时 text 就是全部内容，流式时是全量结果（覆盖校准）
            if (resp.status === 'temp') {
                this.stopTimer();
                if (resp.text) this.content = resp.text;
                this.loading = false;
                this.failed = false;
                this.$nextTick(() => {
                    this.refreshIcons();
                    this.scrollToBottom();
                    this.reflow();
                });
            }
        },

        fail(text) {
            this.stopTimer();
            this.loading = false;
            this.failed = true;
            this.failedText = text || '出错了，稍后再试';
            this.$nextTick(() => {
                this.refreshIcons();
                this.reflow();
            });
        },

        /* ==================== 超时 ==================== */

        startTimer() {
            this.stopTimer();
            this._timer = setTimeout(() => {
                if (this.loading) this.fail('等太久了，没收到回应');
            }, 60000);
        },

        stopTimer() {
            if (this._timer) {
                clearTimeout(this._timer);
                this._timer = null;
            }
        },

        /* ==================== 定位 ==================== */

        /**
         * 贴着选中文字的右侧弹；右边放不下就翻到左侧，最后再夹进视口。
         * 尺寸要等渲染出来才知道，所以是「先显示再测量再摆位」，
         * 由 .is-ready 控制淡入，避免在错误位置闪一帧。
         */
        place() {
            var el = this.$refs.panel;
            if (!el) return;

            var gap = 12;
            var w = el.offsetWidth;
            var h = el.offsetHeight;
            var rect = this._anchor;
            var x, y;

            if (this._pinned) {
                // 用户拖过：位置他说了算，只做越界收敛
                x = this.x;
                y = this.y;
            } else if (rect && (rect.width || rect.height)) {
                x = rect.right + 14;
                y = rect.top - 6;
                if (x + w > window.innerWidth - gap) {
                    x = rect.left - w - 14;
                }
            } else {
                x = (window.innerWidth - w) / 2;
                y = 80;
            }

            this.x = this.clamp(x, gap, window.innerWidth - w - gap);
            this.y = this.clamp(y, gap, window.innerHeight - h - gap);
        },

        /**
         * 内容长高后重收一次底边。
         * 定位当时框里还是「思考中…」，高度只有几十像素；正文一回来能翻好几倍，
         * 底部就这么沉出屏幕了。这里只往上收，不往回放 —— 收是为了看得见，
         * 往下放只会让它再来一次。
         */
        reflow() {
            var el = this.$refs.panel;
            if (!el) return;
            var gap = 12;
            var maxY = window.innerHeight - el.offsetHeight - gap;
            if (this.y > maxY) {
                this.y = Math.max(gap, maxY);
            }
        },

        clamp(v, min, max) {
            if (max < min) return min;
            return Math.max(min, Math.min(v, max));
        },

        scrollToBottom() {
            var body = this.$refs.body;
            if (body) body.scrollTop = body.scrollHeight;
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') lucide.createIcons();
        },

        /* ==================== 拖动 ==================== */

        onHeadDown(e) {
            if (e.button !== 0) return;
            // 关闭按钮是点，不是拖
            if (e.target && e.target.closest && e.target.closest('.sb-tc-close')) return;

            this._drag = {
                id: e.pointerId,
                dx: e.clientX - this.x,
                dy: e.clientY - this.y
            };
            this.dragging = true;
            this._pinned = true;

            var head = this.$refs.head;
            if (head && head.setPointerCapture) {
                try { head.setPointerCapture(e.pointerId); } catch (err) { /* 捕获失败也不影响，后面还有 pointercancel 兜 */ }
            }
            // 不 prevent 的话拖动过程中会把框里的文字刷出一片选区
            e.preventDefault();
        },

        onHeadMove(e) {
            if (!this._drag || e.pointerId !== this._drag.id) return;
            this.x = e.clientX - this._drag.dx;
            this.y = e.clientY - this._drag.dy;
        },

        onHeadUp(e) {
            if (!this._drag) return;
            if (e && e.pointerId != null && e.pointerId !== this._drag.id) return;
            this._drag = null;
            this.dragging = false;
            // 松手时收敛回视口内 —— 拖出一半留在屏幕外的框，用户自己都抓不回来
            this.place();
        },

        /* ==================== 全局监听 ==================== */

        onDocMouseDown(e) {
            if (!this.visible) return;
            if (e.target && e.target.closest && e.target.closest('.sb-tc')) return;
            this.close();
        },

        onKeydown(e) {
            if (this.visible && e.key === 'Escape') this.close();
        }
    },

    mounted() {
        if (typeof WsBus !== 'undefined') {
            this._offFrame = WsBus.on('*', raw => this.onWsFrame(raw));
        }
        document.addEventListener('mousedown', this.onDocMouseDown);
        document.addEventListener('keydown', this.onKeydown);
    },

    beforeUnmount() {
        if (this._offFrame) this._offFrame();
        document.removeEventListener('mousedown', this.onDocMouseDown);
        document.removeEventListener('keydown', this.onKeydown);
        this.stopTimer();
    },

    updated() {
        this.refreshIcons();
    }
};

/* ============================================================
   全局单例挂载
   ============================================================ */
(function () {
    var instance = null;

    function ensure() {
        if (instance) return instance;
        var host = document.createElement('div');
        host.className = 'sb-tc-host';
        document.body.appendChild(host);
        instance = Vue.createApp(TempChat).mount(host);
        return instance;
    }

    window.TempChat = {
        /** 文本长度上限，右键菜单那边读它决定要不要置灰 —— 只此一处定义，别在别处写死 */
        MAX_LENGTH: TEMP_CHAT_MAX_LENGTH,
        ask: function (options) { ensure().ask(options); },
        close: function () { if (instance) instance.close(); }
    };
})();
