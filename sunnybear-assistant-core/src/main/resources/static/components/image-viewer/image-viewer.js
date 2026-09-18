/**
 * 图片查看器（灯箱）
 *
 * 替代原来「点图 → window.open 新标签页」的行为：图片在本页浮层里放大显示，
 * 支持滚轮 / 双指缩放、拖动平移、双击放大与复位、旋转、原始大小。
 *
 * ---------- 用法（全局单例，懒挂载） ----------
 *   ImageViewer.open(url)   // url 必须是能直接访问的地址（本地文件先过 $fileUrl.proxy）
 *   ImageViewer.close()
 *
 * 组件在第一次 open 时才创建实例并挂到 body 上，这么做有两个好处：
 *   · 不用往 index.html 的模板里塞标签，接入面收在 previewImage 一个函数里；
 *   · plug/ 下的独立页面只要引了这个脚本 + 对应 css，同样能用。
 *
 * ---------- 接入点 ----------
 *   1) utils/file-url.js → previewImage()：消息里附件图的 6 处调用全走这一个入口；
 *   2) markdown 正文里的 <img>：见本文件末尾的 document 级事件委托。正文图原先点了
 *      毫无反应（只有样式没有行为），现在统一走同一套查看器。
 *
 * ---------- 交互约定 ----------
 *   · 缩放锚点：滚轮 / 双击以光标位置为锚点，工具条按钮以视口中心为锚点。
 *     以光标为锚点这件事不能省 —— 否则想看清边角得反复「放大 → 拖 → 再放大」。
 *   · 指针事件统一处理：鼠标拖动与触屏拖动 / 捏合共用一套逻辑（Pointer Events），
 *     用 setPointerCapture 把指针锁在图片上，鼠标拖出图片也不会丢事件。
 *   · 拖动时必须关掉 transform 过渡，否则图片追着鼠标跑，手感是「拖泥带水」。
 *
 * ---------- 关于百分比读数 ----------
 *   显示的是「相对原图的真实比例」，不是 transform 的 scale 值：
 *   大图自适应缩进窗口时 scale = 1，但真实显示比例可能只有 30%，
 *   直接显示 scale 会让人误以为看到的就是原始尺寸。
 */
const ImageViewer = {
    name: 'ImageViewer',

    template: `
    <transition name="sb-iv-fade">
        <div v-if="visible" class="sb-iv-overlay"
             :style="{ '--main-color': mainColor }"
             @wheel.prevent="onWheel">
            <div class="sb-iv-stage" ref="stage" @click.self="close">
                <img ref="img"
                     class="sb-iv-img"
                     :class="{ 'is-dragging': dragging, 'is-ready': ready }"
                     :style="imgStyle"
                     :src="src"
                     draggable="false"
                     @load="onLoad"
                     @error="onError"
                     @pointerdown="onPointerDown"
                     @pointermove="onPointerMove"
                     @pointerup="onPointerUp"
                     @pointercancel="onPointerUp"
                     @dblclick.stop="onDblClick">

                <div v-if="!ready && !failed" class="sb-iv-hint">
                    <i data-lucide="loader-circle" class="sb-iv-spin"></i>
                    <span>加载中…</span>
                </div>
                <div v-else-if="failed" class="sb-iv-hint is-error">
                    <i data-lucide="image-off"></i>
                    <span>图片加载失败</span>
                </div>
            </div>

            <button class="sb-iv-btn sb-iv-close" @click="close" title="关闭（Esc）">
                <i data-lucide="x"></i>
            </button>

            <div class="sb-iv-toolbar" @click.stop>
                <button class="sb-iv-btn" @click="zoomBy(1 / 1.25)" title="缩小">
                    <i data-lucide="zoom-out"></i>
                </button>
                <span class="sb-iv-ratio">{{ ratioText }}</span>
                <button class="sb-iv-btn" @click="zoomBy(1.25)" title="放大">
                    <i data-lucide="zoom-in"></i>
                </button>
                <span class="sb-iv-sep"></span>
                <button class="sb-iv-btn" @click="oneToOne" title="原始大小（1:1）">
                    <i data-lucide="scan"></i>
                </button>
                <button class="sb-iv-btn" @click="rotateBy" title="旋转 90°">
                    <i data-lucide="rotate-cw"></i>
                </button>
                <button class="sb-iv-btn" @click="reset" title="复位">
                    <i data-lucide="maximize"></i>
                </button>
            </div>
        </div>
    </transition>`,

    props: {
        mainColor: { type: String, default: '' }
    },

    data() {
        return {
            visible: false,
            src: '',
            ready: false,
            failed: false,

            // 视图变换：先平移、再缩放、最后旋转（transform 从右往左作用）
            scale: 1,
            tx: 0,
            ty: 0,
            rotate: 0,

            dragging: false,

            minScale: 0.2,
            maxScale: 8,
            wheelStep: 1.12,
            btnStep: 1.25
        };
    },

    computed: {
        imgStyle() {
            return {
                transform: 'translate3d(' + this.tx + 'px, ' + this.ty + 'px, 0)'
                    + ' scale(' + this.scale + ')'
                    + ' rotate(' + this.rotate + 'deg)'
            };
        },

        /** 相对原图的真实显示比例；图片还没量到尺寸时退回 scale 本身 */
        ratioText() {
            var img = this.$refs.img;
            var pct = this.scale * 100;
            if (img && img.naturalWidth && img.clientWidth) {
                pct = this.scale * (img.clientWidth / img.naturalWidth) * 100;
            }
            return Math.round(pct) + '%';
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        open(url) {
            if (!url) return;
            this.src = url;
            this.visible = true;
            this.resetView();
            this.ready = false;
            this.failed = false;
            // open 可能在同一个实例上被连续调用（换图），指针状态必须清干净，
            // 否则上一张图残留的指针会让新的拖动从错误基准开始
            this._pointers = new Map();
            this._drag = null;
            this._pinch = null;
            this.$nextTick(() => this.refreshIcons());
        },

        close() {
            this.visible = false;
            this.src = '';
            this.ready = false;
            this.failed = false;
            this._pointers = new Map();
            this._drag = null;
            this._pinch = null;
        },

        onLoad() {
            this.ready = true;
            this.failed = false;
            this.$nextTick(() => this.refreshIcons());
        },

        onError() {
            this.failed = true;
            this.ready = false;
            this.$nextTick(() => this.refreshIcons());
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') lucide.createIcons();
        },

        /* ==================== 视图操作 ==================== */

        resetView() {
            this.scale = 1;
            this.tx = 0;
            this.ty = 0;
            this.rotate = 0;
            this.dragging = false;
        },

        reset() {
            this.resetView();
        },

        rotateBy() {
            this.rotate = (this.rotate + 90) % 360;
        },

        /** 工具条按钮：以视口中心为锚点缩放 */
        zoomBy(factor) {
            this.applyZoom(this.scale * factor, 0, 0);
        },

        /** 缩到原图 1:1：布局宽度是 CSS 上限压过之后的宽度，两者之比就是真实倍率 */
        oneToOne() {
            var img = this.$refs.img;
            if (!img || !img.naturalWidth || !img.clientWidth) return;
            this.applyZoom(img.naturalWidth / img.clientWidth, 0, 0);
        },

        /**
         * 围绕锚点缩放。
         * 推导：设锚点相对图片中心的坐标为 p，元素内对应点 u = (p - t) / s。
         * 要让屏幕位置不动，需 p = t' + u·s'，代入即 t' = p - (p - t) · (s'/s)。
         * @param {number} newScale 目标倍率（内部会夹到 min/max）
         * @param {number} ax       锚点 X，相对 stage 中心
         * @param {number} ay       锚点 Y，相对 stage 中心
         */
        applyZoom(newScale, ax, ay) {
            var s = Math.min(this.maxScale, Math.max(this.minScale, newScale));
            var k = s / this.scale;
            if (k === 1) return;
            this.tx = ax - (ax - this.tx) * k;
            this.ty = ay - (ay - this.ty) * k;
            this.scale = s;
        },

        /* ==================== 坐标换算 ==================== */

        /** 视口坐标 → 相对 stage（也就是图片布局中心）的坐标 */
        toLocal(clientX, clientY) {
            var rect = this.$refs.stage.getBoundingClientRect();
            return {
                x: clientX - rect.left - rect.width / 2,
                y: clientY - rect.top - rect.height / 2
            };
        },

        /* ==================== 滚轮 / 双击 ==================== */

        onWheel(e) {
            if (!this.ready) return;
            var p = this.toLocal(e.clientX, e.clientY);
            var factor = e.deltaY < 0 ? this.wheelStep : 1 / this.wheelStep;
            this.applyZoom(this.scale * factor, p.x, p.y);
        },

        onDblClick(e) {
            if (!this.ready) return;
            // 已经放大了就复位，不然就在双击处放大 —— 两个方向都用同一个手势
            if (this.scale > 1.01) {
                this.resetView();
                return;
            }
            var p = this.toLocal(e.clientX, e.clientY);
            this.applyZoom(2, p.x, p.y);
        },

        /* ==================== 拖动 / 捏合 ==================== */

        onPointerDown(e) {
            if (!this.ready) return;
            this._pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            try { e.target.setPointerCapture(e.pointerId); } catch (err) {}

            if (this._pointers.size === 1) {
                this.dragging = true;
                this._drag = { x: e.clientX, y: e.clientY, tx: this.tx, ty: this.ty };
            } else if (this._pointers.size === 2) {
                // 第二根手指落下：从拖动切到捏合，捏合期间不再平移两次
                this.dragging = false;
                this._drag = null;
                this._pinch = this.buildPinch();
            }
        },

        onPointerMove(e) {
            if (!this._pointers.has(e.pointerId)) return;
            this._pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

            if (this._pointers.size >= 2 && this._pinch) {
                this.updatePinch();
                return;
            }

            if (this._drag && this._pointers.size === 1) {
                this.tx = this._drag.tx + (e.clientX - this._drag.x);
                this.ty = this._drag.ty + (e.clientY - this._drag.y);
            }
        },

        onPointerUp(e) {
            this._pointers.delete(e.pointerId);
            try { e.target.releasePointerCapture(e.pointerId); } catch (err) {}

            if (this._pointers.size < 2) this._pinch = null;

            if (this._pointers.size === 1) {
                // 两指松掉一根：以当前状态为基准，接着单指拖动，避免跳一下
                var p = this._pointers.values().next().value;
                this._drag = { x: p.x, y: p.y, tx: this.tx, ty: this.ty };
                this.dragging = true;
            } else if (this._pointers.size === 0) {
                this._drag = null;
                this.dragging = false;
            }
        },

        /** 记录捏合起点：两指距离 + 中点（中点用相对 stage 的坐标） */
        buildPinch() {
            var pts = Array.from(this._pointers.values());
            var mid = this.midpoint(pts[0], pts[1]);
            var local = this.toLocal(mid.x, mid.y);
            return {
                dist: this.distance(pts[0], pts[1]),
                x: local.x,
                y: local.y,
                scale: this.scale,
                tx: this.tx,
                ty: this.ty
            };
        },

        /**
         * 捏合：距离变化 → 围绕捏合中点缩放，中点位移 → 跟随平移。
         * 缩放按起点比例一次算到位（不累乘），避免手指来回时误差堆积。
         */
        updatePinch() {
            var pts = Array.from(this._pointers.values());
            var p1 = pts[0], p2 = pts[1];
            var start = this._pinch;
            if (!start.dist) return;

            var mid = this.midpoint(p1, p2);
            var local = this.toLocal(mid.x, mid.y);
            var ratio = this.distance(p1, p2) / start.dist;
            var s = Math.min(this.maxScale, Math.max(this.minScale, start.scale * ratio));
            var k = s / start.scale;

            this.scale = s;
            this.tx = start.x - (start.x - start.tx) * k + (local.x - start.x);
            this.ty = start.y - (start.y - start.ty) * k + (local.y - start.y);
        },

        midpoint(a, b) {
            return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        },

        distance(a, b) {
            var dx = a.x - b.x;
            var dy = a.y - b.y;
            return Math.sqrt(dx * dx + dy * dy);
        },

        /* ==================== 键盘 ==================== */

        onKeydown(e) {
            if (!this.visible) return;
            if (e.key === 'Escape') {
                e.stopPropagation();
                this.close();
            }
        }
    },

    mounted() {
        this._pointers = new Map();
        this._drag = null;
        this._pinch = null;
        document.addEventListener('keydown', this.onKeydown);
    },

    beforeUnmount() {
        document.removeEventListener('keydown', this.onKeydown);
    },

    updated() {
        this.refreshIcons();
    }
};

/* ============================================================
   全局单例挂载 + markdown 正文图的事件委托
   ============================================================ */
(function () {
    var instance = null;

    function ensure() {
        if (instance) return instance;
        // 组件自己的样式由页面 <link> 引入；这里只负责造一个宿主节点
        var host = document.createElement('div');
        host.className = 'sb-iv-host';
        document.body.appendChild(host);
        instance = Vue.createApp(ImageViewer).mount(host);
        return instance;
    }

    window.ImageViewer = {
        open: function (url) {
            if (!url) return;
            ensure().open(url);
        },
        close: function () {
            if (instance) instance.close();
        }
    };

    /* markdown 正文图：内容是 v-html 塞进来的，没法逐个绑事件，就在 document 上兜一层。
       只认 .markdown-body 里的 <img>，并避开灯箱自己那张图（它不在 markdown-body 里，
       但多一层判断不亏）。src 在渲染时已经过 BASE_PATH 重写，是能直接访问的地址。 */
    document.addEventListener('click', function (e) {
        var el = e.target;
        if (!el || el.tagName !== 'IMG' || !el.closest) return;
        if (!el.closest('.markdown-body')) return;
        if (el.closest('.sb-iv-overlay')) return;
        var url = el.currentSrc || el.src;
        if (!url) return;
        e.preventDefault();
        window.ImageViewer.open(url);
    });
})();
