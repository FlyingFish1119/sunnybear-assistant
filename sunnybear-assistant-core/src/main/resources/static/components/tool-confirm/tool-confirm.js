/**
 * 工具确认弹窗组件（自包含版 · 多标签）
 *
 * 将 WebSocket 通信、图标/标题映射、markdown 渲染、倒计时全部内聚在组件内部。
 * 组件自行通过 inject('wsBus') 订阅 ###TOOL_ASK### 信号并调用 show()，
 * 父组件无需再转发该信号；入口按钮仍可通过 ref 调用 expand()。
 *
 * Props:
 *   mainColor      — String     主题色
 *
 * Injects:
 *   wsBus          — WebSocket 消息总线（app.provide('wsBus', WsBus)），用于订阅 ###TOOL_ASK###
 *
 * 关闭语义：点遮罩 / Esc / 右上角 X 均为"收起"（不拒绝），待确认项保留在队列中，
 * 收起后由页面顶部的入口按钮（角标显示待确认数量）再次调 expand() 打开。
 * 收起状态下来的新请求不会自动展开，只累加角标；只有"拒绝"按钮才回传 confirm:false。
 *
 * 公开方法（通过 ref 调用）：
 *   show(toolAsk)  — 弹出/追加确认对话框，toolAsk 为服务端下发的 { id, toolName, message, timeout } 对象
 *   expand()       — 重新展开收起的弹窗（供顶部入口按钮调用）
 *
 * Emits:
 *   pending-change — 待确认数量变化时触发，参数为当前未处理数量（供父组件渲染入口角标）
 */
const ToolConfirm = {
    name: 'ToolConfirm',

    template: `
    <div v-if="visible" class="tool-confirm-overlay" @click.self="collapse">
      <div class="tool-confirm-dialog"
           tabindex="-1"
           ref="dialog"
           @keydown.esc="collapse"
           @keydown.enter.ctrl.prevent="accept">
        <!-- 标签栏：多个并发确认以标签页形式共存，可切换 -->
        <div class="tool-confirm-tabs" role="tablist" :style="{'--main-color': mainColor}">
          <button v-for="(ask, idx) in asks" :key="ask.id"
                  class="tool-confirm-tab"
                  :class="{ 'is-active': idx === activeIndex }"
                  role="tab"
                  :aria-selected="idx === activeIndex"
                  @click="selectTab(idx)">
            <i :data-lucide="ask.icon"></i>
            <span class="tool-confirm-tab-title">{{ ask.title }}</span>
            <span class="tool-confirm-tab-countdown"
                  :class="{ 'is-danger': ask.hasTimeout && ask.countdown <= 3 }">{{ ask.hasTimeout ? ask.countdown : '∞' }}</span>
          </button>
        </div>
        <!-- 内容区：只渲染当前激活的标签。
             注意：各区块必须保持为弹窗 flex 容器的直接子项，
             包一层 v-if 中间层会使 .tool-confirm-body 的 flex 收缩失效，footer 会被挤出对话框 -->
        <div v-if="activeAsk" class="tool-confirm-header">
          <div class="tool-confirm-icon-wrap">
            <i :data-lucide="activeAsk.icon"></i>
          </div>
          <div class="tool-confirm-header-text">
            <span class="tool-confirm-title">{{ activeAsk.title }}</span>
            <span class="tool-confirm-subtitle">AI 请求执行以下操作，请确认是否允许</span>
          </div>
          <button class="tool-confirm-close" @click="collapse" title="收起（稍后处理）">
            <i data-lucide="x"></i>
          </button>
        </div>
        <div v-if="activeAsk" class="tool-confirm-body">
          <div class="markdown-body" v-html="activeAsk.renderedMessage"></div>
        </div>
        <div v-if="activeAsk" class="tool-confirm-progress">
          <div class="tool-confirm-progress-track">
            <div class="tool-confirm-progress-bar"
                 :style="activeAsk.hasTimeout ? {width: activeAsk.progressPercent + '%', '--main-color': mainColor} : {'--main-color': mainColor}"
                 :class="{
                   'is-warning': activeAsk.hasTimeout && activeAsk.progressPercent <= 30,
                   'is-danger': activeAsk.hasTimeout && activeAsk.progressPercent <= 10,
                   'is-indeterminate': !activeAsk.hasTimeout
                 }">
            </div>
          </div>
          <span class="tool-confirm-progress-text">
            {{ activeAsk.hasTimeout ? activeAsk.countdown + ' 秒后自动拒绝' : '等待确认中…' }}
          </span>
        </div>
        <div class="tool-confirm-footer">
          <button class="tool-confirm-btn tool-confirm-btn-cancel" @click="reject">
            <i data-lucide="shield-x" style="width:16px;height:16px"></i>
            <span>拒绝</span>
          </button>
          <button class="tool-confirm-btn tool-confirm-btn-accept"
                  @click="accept"
                  :style="{'--main-color': mainColor}">
            <i data-lucide="shield-check" style="width:16px;height:16px"></i>
            <span>允许执行</span>
          </button>
        </div>
      </div>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['pending-change'],

    inject: {
        // 可选注入：插件页未提供 wsBus 时降级为 null（仍可由父组件通过 ref 调用 show()）
        wsBus: { default: null }
    },

    data() {
        return {
            // 待确认的标签队列：每项独立 id/标题/倒计时/timer，互不干扰
            asks: [],
            // 当前激活标签的下标，-1 表示无
            activeIndex: -1,
            // 用户是否主动收起了弹窗：收起后待确认项保留，新请求不自动展开只累加角标
            collapsed: false,
            // 工具名 → 图标 / 标题映射
            iconMap: {
                'command_tool': 'terminal',
                'file_write_tool': 'file-pen-line',
                'file_edit_tool': 'file-code',
                'file_delete_tool': 'trash-2',
                'task_run_tool': 'play-circle',
                'net_explore_tool': 'globe',
                'file_explore_tool': 'folder-search',
            },
            titleMap: {
                'command_tool': '命令执行确认',
                'file_write_tool': '文件写入确认',
                'file_edit_tool': '文件编辑确认',
                'file_delete_tool': '文件删除确认',
                'task_run_tool': '任务执行确认',
                'net_explore_tool': '网络探索确认',
                'file_explore_tool': '目录探索确认',
            }
        };
    },

    computed: {
        visible() {
            return this.asks.length > 0 && !this.collapsed;
        },
        activeAsk() {
            return this.asks[this.activeIndex] || null;
        },
        // 待确认数量（供父组件入口角标使用）
        pendingCount() {
            return this.asks.length;
        }
    },

    watch: {
        // 待确认数量变化 → 通知父组件刷新顶部入口角标
        pendingCount(val) {
            this.$emit('pending-change', val);
        }
    },

    methods: {
        /* ---- 公开方法 ---- */
        show(toolAsk) {
            // 防御：同一 id 重复推送（如 WebSocket 重连后的重复消息）直接忽略
            if (this.asks.some(a => a.id === toolAsk.id)) return;
            // timeout 为空/0/负数 → 不超时，一直等待用户确认；有效正整数 → 按该秒数倒计时
            const timeout = Number(toolAsk.timeout);
            const hasTimeout = timeout > 0;
            const total = hasTimeout ? Math.round(timeout) : 0;
            const tab = {
                id: toolAsk.id,
                title: this.titleMap[toolAsk.toolName] || toolAsk.toolName || '工具执行确认',
                icon: this.iconMap[toolAsk.toolName] || 'wrench',
                renderedMessage: MarkdownUtils.render(toolAsk.message || ''),
                hasTimeout: hasTimeout,
                total: total,
                countdown: total,
                progressPercent: 100,
                resolved: false,
                _timer: null
            };
            this.asks.push(tab);
            // 浏览器行为：新标签自动激活，但收起状态下来新请求只累加角标、不打扰用户
            if (!this.collapsed) {
                this.activeIndex = this.asks.length - 1;
            }
            this.$nextTick(() => {
                if (!this.asks.includes(tab)) return; // 防御：期间标签已被移除
                this.startCountdown(tab);
                this.focusDialog();
            });
        },

        // 重新展开收起的弹窗（顶部入口按钮调用）
        expand() {
            if (this.asks.length === 0) return;
            this.collapsed = false;
            this.$nextTick(() => this.focusDialog());
        },

        /* ---- 内部方法 ---- */
        // 每标签独立倒计时，全部并行走（与服务端每 ask 独立超时一致）
        startCountdown(tab) {
            this.clearTimer(tab);
            // 注意：入参 tab 可能是 push 进响应式数组前的"原始对象"，
            // 直接改原始对象不会触发 Vue 视图更新（进度条/倒计时会冻住）。
            // 这里从响应式数组中取回 proxy 引用，保证倒计时能实时刷新。
            const t = this.asks.find(a => a.id === tab.id) || tab;
            // 无超时：不启动倒计时，进度条进入等待态（由 CSS 不定态动画呈现）
            if (!t.hasTimeout) {
                t.countdown = 0;
                t.progressPercent = 100;
                return;
            }
            t.countdown = t.total;
            t.progressPercent = 100;
            t._timer = setInterval(() => {
                t.countdown--;
                t.progressPercent = Math.round((t.countdown / t.total) * 100);
                if (t.countdown <= 0) {
                    // 超时自动拒绝：只拒绝自己（用本标签自己的 id 发送）
                    this.resolveTab(t.id, false);
                }
            }, 1000);
        },

        clearTimer(tab) {
            if (tab._timer) {
                clearInterval(tab._timer);
                tab._timer = null;
            }
        },

        // 解决（接受/拒绝/超时）一个标签：用其自身 id 发送确认结果，然后移除
        resolveTab(id, confirm) {
            const tab = this.asks.find(a => a.id === id);
            if (!tab || tab.resolved) return;
            tab.resolved = true;
            API.chat.confirm({ id: tab.id, confirm })
                .catch(err => console.error('确认请求发送失败:', err));
            this.removeTab(id);
        },

        removeTab(id) {
            const idx = this.asks.findIndex(a => a.id === id);
            if (idx === -1) return;
            this.clearTimer(this.asks[idx]);
            this.asks.splice(idx, 1);
            if (this.asks.length === 0) {
                // 全部解决 → 整个弹窗关闭，并复位收起态，下一次新请求可自动展开
                this.activeIndex = -1;
                this.collapsed = false;
                return;
            }
            // 浏览器行为：删除激活标签 → 激活右侧邻居；删除背景标签 → 激活项不变或左移
            this.activeIndex = Math.min(idx, this.asks.length - 1);
            this.$nextTick(() => this.focusDialog());
        },

        accept() {
            const t = this.activeAsk;
            if (!t) return;
            // 防连击：解决标签后新标签会自动激活，双击可能误确认下一个标签，加 300ms 窗口
            if (this._resolving) return;
            this._resolving = true;
            this.resolveTab(t.id, true);
            setTimeout(() => { this._resolving = false; }, 300);
        },

        reject() {
            const t = this.activeAsk;
            if (!t) return;
            if (this._resolving) return;
            this._resolving = true;
            this.resolveTab(t.id, false);
            setTimeout(() => { this._resolving = false; }, 300);
        },

        // 收起：不拒绝，待确认项保留在队列中，可由顶部入口按钮重新展开
        collapse() {
            if (this.asks.length === 0) return;
            this.collapsed = true;
        },

        selectTab(idx) {
            this.activeIndex = idx;
            this.$nextTick(() => this.focusDialog());
        },

        focusDialog() {
            const el = this.$refs.dialog;
            if (el) el.focus();
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') {
                this.$nextTick(() => lucide.createIcons());
            }
        }
    },

    mounted() {
        // 防御：父组件在组件挂载前调用 show() 的极端情况
        this.asks.forEach(t => this.startCountdown(t));
        // 自行订阅 ###TOOL_ASK### 信号（wsBus 由 app.provide 注入）
        if (this.wsBus) {
            this._unsubToolAsk = this.wsBus.on('TOOL_ASK', (payload) => {
                try {
                    this.show(JSON.parse(payload));
                } catch (e) {
                    console.error('解析 TOOL_ASK 失败:', e);
                }
            });
        }
    },

    updated() {
        this.refreshIcons();
    },

    beforeUnmount() {
        this.asks.forEach(t => this.clearTimer(t));
        if (this._unsubToolAsk) {
            this._unsubToolAsk();
            this._unsubToolAsk = null;
        }
    }
};
