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
            /**
             * 右键目标：image / link / bubble 三个 DOM 引用，命中哪个算哪个（可能都为空）。
             * 在 contextmenu 那一刻判一次，整份菜单都按它筛 —— 菜单弹出来之后鼠标位置
             * 就没意义了，别等点菜单项时再回头猜刚才右键在哪。
             */
            target: { image: null, link: null, bubble: null },
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

        /**
         * 右键那条消息的原始 Markdown 源。
         * 从气泡上的 data-msg-id 反查 store，而不是从 DOM 文本倒推 ——
         * DOM 是渲染后的结果，代码块、表格、嵌套列表这些回不去原来的写法。
         * 返回空串时菜单项直接不出现。
         */
        messageMarkdown() {
            var bubble = this.target.bubble;
            if (!bubble || typeof SessionStore === 'undefined') return '';

            var id = bubble.getAttribute('data-msg-id');
            if (!id) return '';

            var list = (SessionStore.state && SessionStore.state.currentMessages) || [];
            var msg = null;
            for (var i = 0; i < list.length; i++) {
                if (list[i] && String(list[i].id) === String(id)) { msg = list[i]; break; }
            }
            if (!msg || !Array.isArray(msg.contents)) return '';

            var parts = [];
            for (var j = 0; j < msg.contents.length; j++) {
                var c = msg.contents[j];
                if (c && c.type === 'text' && c.content) parts.push(c.content);
            }
            return parts.join('\n\n').trim();
        },

        /**
         * 菜单项清单 —— 按右键目标动态筛。
         *
         * 两条规矩：
         *   · 上下文不适用的项**直接不显示**（没选中文字就别摆一列灰的），
         *     一个都不适用时整个菜单不弹（见 open）；
         *   · 只有「内容本身不合法」才置灰 + 写明原因（比如选中文字太长）——
         *     那种"看得见但点不了"必须给用户一个解释。
         *
         * 顺序：通用项在前（复制永远第一项，照顾肌肉记忆），目标特异的在后。
         */
        items() {
            var self = this;
            var list = [];

            // ---- 通用项：选中文字才有 ----
            if (this.canCopy) {
                list.push({
                    id: 'copy',
                    icon: 'copy',
                    label: '复制',
                    run: function () { self.copySelection(); }
                });
                list.push({
                    id: 'explain',
                    icon: 'sparkles',
                    // 超长（护栏级别，正常选中碰不到）就置灰，并在标签上写明原因 ——
                    // 光置灰不解释，用户只会以为功能坏了
                    label: this.explainTooLong ? '快速解释（内容过多）' : '快速解释',
                    disabled: this.explainTooLong,
                    run: function () { self.explainSelection(); }
                });
            }

            // ---- 消息气泡上：复制原始 Markdown ----
            if (this.messageMarkdown) {
                list.push({
                    id: 'copy-md',
                    icon: 'file-code-2',
                    label: '复制为 Markdown',
                    run: function () { self.copyMessageMarkdown(); }
                });
            }

            // ---- 图片上 ----
            if (this.target.image) {
                list.push({
                    id: 'save-image',
                    icon: 'download',
                    label: '图片另存为',
                    run: function () { self.saveImage(); }
                });
            }

            // ---- 链接上 ----
            if (this.target.link) {
                list.push({
                    id: 'open-link',
                    icon: 'external-link',
                    label: '在浏览器打开',
                    run: function () { self.openLink(); }
                });
                list.push({
                    id: 'copy-link',
                    icon: 'link',
                    label: '复制链接地址',
                    run: function () { self.copyLink(); }
                });
            }

            return list;
        }
    },

    methods: {
        /* ==================== 开关 ==================== */

        /** @param {MouseEvent} e contextmenu 事件（open 时立刻把选区读下来） */
        open(e) {
            this.selectedText = this.readSelection();
            this._savedRange = this.readRange();
            this.target = this.resolveTarget(e.target);

            // 一个适用项都没有就别弹了 —— 摆一列灰的比不弹更让人困惑
            if (!this.items.length) return;

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

        /**
         * 认一下这次右键落在什么上。只认三类：图片、链接、消息气泡 ——
         * 认不出来的（普通文字、空白）一律为空对象，通用项照常出现。
         * 用 closest 而不是 === 判断：点在链接里的图标上、图片里的文字上，都该算命中。
         */
        resolveTarget(node) {
            var empty = { image: null, link: null, bubble: null };
            if (!node || node.nodeType !== 1 || typeof node.closest !== 'function') return empty;
            return {
                image: node.closest('img'),
                link: node.closest('a[href]'),
                bubble: node.closest('.message-area-bubble')
            };
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

        /* ==================== 图片 / 链接 / 消息 ==================== */

        /**
         * 图片另存为。
         * 图片走 /file/proxy 是同源的，a[download] 直接生效，
         * 不用绕 fetch + blob + createObjectURL 那一圈。
         */
        saveImage() {
            var img = this.target.image;
            this.close();
            if (!img || !img.src) return;

            var a = document.createElement('a');
            a.href = img.src;
            a.download = this.guessFileName(img.src);
            // 必须挂进文档再点，否则部分浏览器会直接忽略这次点击
            document.body.appendChild(a);
            a.click();
            a.remove();
        },

        /**
         * 猜个像样的文件名：本地图片的地址是 /file/proxy?path=会话ID:文件名，
         * 冒号后面那截就是原始文件名。实在猜不出来再造一个 ——
         * 别让用户存下来一个 download.bin。
         */
        guessFileName(src) {
            var stamp = Date.now();
            try {
                var u = new URL(src, location.href);
                var ref = u.searchParams.get('path') || '';
                var cut = ref.lastIndexOf(':');
                var name = cut >= 0 ? ref.slice(cut + 1) : '';
                if (name) return name;

                var seg = u.pathname.split('/').pop();
                if (seg && seg.indexOf('.') > 0) return seg;
            } catch (e) {
                // 解析不了就继续往下兜
            }
            // data: URL 至少还知道格式
            var m = /^data:image\/([\w+.-]+)/.exec(src);
            if (m) return 'image-' + stamp + '.' + m[1].replace('jpeg', 'jpg');
            return 'image-' + stamp + '.png';
        },

        /** 复制链接地址。用 a.href 而不是 getAttribute('href') —— DOM 属性拿到的是绝对地址 */
        copyLink() {
            var a = this.target.link;
            this.close();
            if (!a || !a.href) return;

            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(a.href).then(
                    function () { if (window.SbToast) window.SbToast.success('已复制链接地址'); },
                    function () { if (window.SbToast) window.SbToast.error('复制链接失败'); }
                );
            } else if (window.SbToast) {
                window.SbToast.error('复制链接失败');
            }
        },

        /**
         * 在浏览器打开链接。
         * PWA 窗口里开外部地址，Chromium 会交给系统默认浏览器；
         * noopener 不能省 —— 不留 window.opener，免得对面页面能反向操作本窗口。
         */
        openLink() {
            var a = this.target.link;
            this.close();
            if (!a || !a.href) return;
            window.open(a.href, '_blank', 'noopener');
        },

        /* ==================== 选区 → Markdown 片段 ==================== */

        /**
         * 把 Markdown 源压成「肉眼能看到的字符」，并记下每个字符在原文里的下标。
         *
         * 为什么绕这一圈：DOM 里选中的文字，在 md 源里**根本搜不到** ——
         * `## ` 前缀、`**`、链接的 `](url)` 这些渲染后都不可见，直接 indexOf 必然落空。
         * 那就两边都按同一套规则去噪：抹掉不可见的部分，只留实义字符。
         * 规则一致就能对齐；对齐之后靠映射表把位置还原回原文下标。
         *
         * 图片和链接先「原地换成等长空格」，这样逐字符扫的时候下标还走在原位上。
         */
        buildMdIndex(md) {
            var src = String(md || '')
                .replace(/!\[[^\]]*\]\([^)]*\)/g, function (m) {
                    return ' '.repeat(m.length);      // 图片在 DOM 里没有对应文字，整段抹掉
                })
                .replace(/\[([^\]]*)\]\([^)]*\)/g, function (m, text) {
                    return ' ' + text + ' '.repeat(m.length - text.length - 1);   // 链接只留文字
                });

            var chars = [];
            var map = [];
            for (var i = 0; i < src.length; i++) {
                var ch = src.charAt(i);
                if (/\s/.test(ch)) continue;
                if ('`*_~#>|[]()-+'.indexOf(ch) >= 0) continue;
                chars.push(ch);
                map.push(i);
            }
            return { text: chars.join(''), map: map };
        },

        /** 跟 buildMdIndex 同一套去噪规则，用来把选区文本压成同样的形式 */
        normalizeText(s) {
            return String(s || '').replace(/\s+/g, '').replace(/[`*_~#>|\[\]()\-+]/g, '');
        },

        /**
         * 从整条消息的 md 源里截出选区对应的那一截。
         *
         * 精度说明：不追求逐字还原。整段选中是准的；跨格式的精细选区可能
         * 多带少带一两个符号（这点偏差可以接受）。定位不上就返回空串 ——
         * 宁可退化成纯文本，也别给用户一段对不上的东西。
         *
         * 已知短板：同一段文字在消息里出现多次时，只会命中第一处。
         */
        sliceMarkdownBySelection(md, selectedText) {
            var needle = this.normalizeText(selectedText);
            if (!needle) return '';

            var index = this.buildMdIndex(md);
            var at = index.text.indexOf(needle);
            if (at < 0) return '';

            var from = index.map[at];
            var to = index.map[at + needle.length - 1] + 1;
            return md.slice(from, to).trim();
        },

        /**
         * 复制 Markdown：选中了文字就只截那一截，没选中就是整条。
         * （内容在 computed 里取，先取再关，别关完再读）
         */
        copyMessageMarkdown() {
            var full = this.messageMarkdown;
            var picked = this.selectedText.trim();
            this.close();
            if (!full) return;

            var out = full;
            var fellBack = false;
            if (picked) {
                out = this.sliceMarkdownBySelection(full, picked);
                if (!out) {
                    // 对不上（选区跨了图片、或原文里找不到这段）→ 给纯文本，别给错的
                    out = picked;
                    fellBack = true;
                }
            }
            if (!out) return;

            var tip = fellBack ? '已复制纯文本（这段没法还原成 Markdown）' : '已复制 Markdown';
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(out).then(
                    function () { if (window.SbToast) window.SbToast.success(tip); },
                    function () { if (window.SbToast) window.SbToast.error('复制失败'); }
                );
            } else if (window.SbToast) {
                window.SbToast.error('复制失败');
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
