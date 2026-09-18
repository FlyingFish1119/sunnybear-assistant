/**
 * SunnyBear 全局右键菜单
 *
 * 替代浏览器原生右键菜单：在 document 上监听 contextmenu，把菜单弹在鼠标位置。
 *
 * ---------- 与 utils/disable-native-menu.js 的分工 ----------
 *   那个脚本负责「拦住原生菜单」，本组件负责「弹自己的菜单」，两边共用同一套
 *   放行判定（window.NativeMenuGuard.shouldPass）——
 *   规则只在一处定义，否则输入框那类放行区迟早出现两套判断不同步。
 *   放行区（input / textarea / contenteditable / data-native-menu）不弹本菜单，
 *   那边归系统菜单管；自带菜单区（data-own-menu，如侧边栏会话项）也不弹，
 *   那边归它自己那份菜单管。
 *
 * ---------- 加菜单项 ----------
 *   往 computed.items 里加一条即可：{ id, icon, label, disabled, run }。
 *   icon 用 lucide 名字，disabled 为 true 时置灰且不可点。
 *
 * ---------- 几个容易踩的点 ----------
 *   · 选中文字必须在 contextmenu 那一刻读出来：菜单项一点，焦点/选区就变了，
 *     等点击时再读 getSelection() 往往已经是空的。
 *   · 菜单项上挂 @mousedown.prevent：否则按下去的瞬间选区被清掉，
 *     虽然我们已经存了文本不至于复制失败，但用户会看着高亮消失、心里没底。
 *   · 关闭监听用 mousedown 而不是 click：click 要等鼠标抬起，反应慢半拍。
 *   · 菜单自身 @contextmenu.prevent.stop：在菜单上再按右键不该重弹一层，
 *     也不该把事件放给底下的页面。
 *
 * 范围：只给 index.html 引。插件页自包含，不引。
 */
const ContextMenu = {
    name: 'ContextMenu',

    template: `
    <div v-if="visible"
         ref="menu"
         class="sb-cm"
         :class="{ 'is-ready': ready }"
         :style="posStyle"
         @contextmenu.prevent.stop>
        <button v-for="item in items"
                :key="item.id"
                class="sb-cm-item"
                :class="{ 'is-disabled': item.disabled }"
                :disabled="item.disabled"
                @mousedown.prevent
                @click="onItemClick(item)">
            <i :data-lucide="item.icon"></i>
            <span>{{ item.label }}</span>
        </button>
    </div>`,

    data() {
        return {
            visible: false,
            ready: false,
            x: 0,
            y: 0,
            /** 右键那一刻的选中文字（原始文本，没 trim） */
            selectedText: '',
            /** 右键那一刻的选区快照：复制前塞回去，等于用户正选着它按了 Ctrl+C */
            _savedRange: null
        };
    },

    computed: {
        posStyle() {
            return { left: this.x + 'px', top: this.y + 'px' };
        },

        canCopy() {
            return !!this.selectedText.trim();
        },

        /**
         * 选中内容是否超出快速解释的长度上限。
         * 上限本身定义在 temp-chat 那边（TEMP_CHAT_MAX_LENGTH），这里只读不写死 ——
         * 免得两边各存一个数字，改了一处忘另一处。
         */
        explainTooLong() {
            var max = window.TempChat && window.TempChat.MAX_LENGTH;
            if (!max) return false;
            return this.selectedText.trim().length > max;
        },

        /** 菜单项清单 —— 以后加功能就往这里加一条 */
        items() {
            var self = this;
            return [
                {
                    id: 'copy',
                    icon: 'copy',
                    label: '复制',
                    disabled: !this.canCopy,
                    run: function () { self.copySelection(); }
                },
                {
                    id: 'explain',
                    icon: 'sparkles',
                    // 超长（护栏级别，正常选中碰不到）就置灰，并在标签上写明原因 ——
                    // 光置灰不解释，用户只会以为功能坏了
                    label: this.explainTooLong ? '快速解释（内容过多）' : '快速解释',
                    disabled: !this.canCopy || this.explainTooLong,
                    run: function () { self.explainSelection(); }
                }
            ];
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        /** @param {MouseEvent} e contextmenu 事件（open 时立刻把选区读下来） */
        open(e) {
            this.selectedText = this.readSelection();
            this._savedRange = this.readRange();
            this.x = e.clientX;
            this.y = e.clientY;
            this.visible = true;
            this.ready = false;
            this.$nextTick(() => {
                this.clampToViewport();
                this.ready = true;
                this.refreshIcons();
            });
        },

        close() {
            this.visible = false;
            this.ready = false;
        },

        /** 当前选中的文字；没选区时返回空串 */
        readSelection() {
            var sel = window.getSelection();
            return sel ? String(sel) : '';
        },

        /**
         * 选区快照。浏览器原生复制只认「当前选区」，中途被别的东西碰掉就白搭，
         * 所以复制前把这份快照塞回去 —— 等于用户此刻正选着这段文字按了 Ctrl+C。
         */
        readRange() {
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0) return null;
            try {
                return sel.getRangeAt(0).cloneRange();
            } catch (e) {
                return null;
            }
        },

        /** 贴边翻转：菜单不能溢出视口，否则右边/底边右键会看不见半截 */
        clampToViewport() {
            var el = this.$refs.menu;
            if (!el) return;
            var gap = 8;
            var w = el.offsetWidth;
            var h = el.offsetHeight;
            this.x = Math.max(gap, Math.min(this.x, window.innerWidth - w - gap));
            this.y = Math.max(gap, Math.min(this.y, window.innerHeight - h - gap));
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') lucide.createIcons();
        },

        /* ==================== 菜单项 ==================== */

        onItemClick(item) {
            if (!item || item.disabled || !item.run) return;
            item.run();
        },

        /**
         * 复制选中内容。
         *
         * 走 document.execCommand('copy') —— 等价于替用户按了一次 Ctrl+C，由浏览器
         * 自己写剪贴板：text/html + text/plain 双格式，还会把计算样式内联进去
         * （这就是粘到 Word 里格式还在的原因）。自己用 writeText 只能写纯文本，
         * 那份保真是复刻不出来的。
         *
         * execCommand 是废弃 API，但各家浏览器都还认，且必须同步跑在用户手势的
         * 调用栈里 —— 从点菜单到这儿一路同步，正好满足。
         */
        copySelection() {
            var text = this.selectedText;
            if (!text) return;

            // 原生复制只认「当前选区」，先把快照塞回去，别指望它还在
            var sel = window.getSelection();
            if (this._savedRange && sel) {
                try {
                    sel.removeAllRanges();
                    sel.addRange(this._savedRange);
                } catch (e) {
                    // 快照失效（比如原节点已被重渲染掉）就算了，后面还有兜底
                }
            }

            var done = false;
            try {
                done = document.execCommand('copy');
            } catch (e) {
                done = false;
            }

            this.close();

            if (done) {
                if (window.SbToast) window.SbToast.success('已复制');
                return;
            }

            // 兜底：原生复制没成，至少把纯文本给出去，别让用户点了没反应。
            // clipboard API 需要安全上下文（https 或 localhost），本机跑在 localhost 上没问题
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(
                    function () { if (window.SbToast) window.SbToast.success('已复制'); },
                    function () { if (window.SbToast) window.SbToast.error('复制失败，改用 Ctrl+C 试试'); }
                );
            } else {
                if (window.SbToast) window.SbToast.error('复制失败，改用 Ctrl+C 试试');
            }
        },

        /** 快速解释：把选中文字丢给临时问答小框（components/temp-chat） */
        explainSelection() {
            var text = this.selectedText.trim();
            if (!text) return;
            // 锚点要在关菜单之前取 —— 关掉菜单后选区可能就变了
            var anchor = this.selectionRect();
            this.close();
            if (!window.TempChat) {
                if (window.SbToast) window.SbToast.error('解释组件未加载');
                return;
            }
            window.TempChat.ask({ mode: 'temp_what_is_this', content: text, anchor: anchor });
        },

        /** 当前选区的包围盒，用来把小框摆在文字旁边；拿不到返回 null */
        selectionRect() {
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0) return null;
            try {
                var rect = sel.getRangeAt(0).getBoundingClientRect();
                return (rect && (rect.width || rect.height)) ? rect : null;
            } catch (e) {
                return null;
            }
        },

        /* ==================== 全局监听 ==================== */

        /** 点菜单以外的地方就关；点菜单内部交给菜单项自己处理 */
        onDocMouseDown(e) {
            if (!this.visible) return;
            if (e.target && e.target.closest && e.target.closest('.sb-cm')) return;
            this.close();
        },

        onKeydown(e) {
            if (this.visible && e.key === 'Escape') this.close();
        },

        /** 页面这一滚，菜单锚点就飘了，直接收掉 */
        onWheel() {
            if (this.visible) this.close();
        }
    },

    mounted() {
        document.addEventListener('mousedown', this.onDocMouseDown);
        document.addEventListener('keydown', this.onKeydown);
        document.addEventListener('wheel', this.onWheel, { passive: true });
    },

    beforeUnmount() {
        document.removeEventListener('mousedown', this.onDocMouseDown);
        document.removeEventListener('keydown', this.onKeydown);
        document.removeEventListener('wheel', this.onWheel);
    },

    updated() {
        this.refreshIcons();
    }
};

/* ============================================================
   全局单例挂载 + contextmenu 兜底监听
   ============================================================ */
(function () {
    var instance = null;

    function ensure() {
        if (instance) return instance;
        var host = document.createElement('div');
        host.className = 'sb-cm-host';
        document.body.appendChild(host);
        instance = Vue.createApp(ContextMenu).mount(host);
        return instance;
    }

    window.ContextMenu = {
        open: function (e) { ensure().open(e); },
        close: function () { if (instance) instance.close(); }
    };

    document.addEventListener('contextmenu', function (e) {
        // 放行区（输入框等）归原生菜单管，这里不插手 —— 否则两套菜单会同时冒出来
        if (window.NativeMenuGuard && window.NativeMenuGuard.shouldPass(e.target)) return;
        // 自带菜单区（侧边栏会话项等）归它自己那份菜单管，同理让路
        if (window.NativeMenuGuard && window.NativeMenuGuard.hasOwnMenu(e.target)) return;
        // disable-native-menu.js 已经 prevent 过一次，这里再拦一次是兜底：
        // 万一那个脚本没被引入，自定义菜单也必须能顶掉原生菜单
        e.preventDefault();
        window.ContextMenu.open(e);
    });
})();
