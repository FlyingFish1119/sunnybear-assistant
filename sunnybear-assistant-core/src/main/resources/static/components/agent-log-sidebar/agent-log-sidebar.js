/**
 * Agent Log 组件 — 顶栏开关 + 右侧子 Agent 执行日志浮窗
 *
 * 以「插件」形态存在：核心页 index.html 通过
 *   TopbarPlugins.registerSlot('topbar-right', AgentLogSidebar, 0)
 * 把它挂进顶栏右栏；插件页不注册即完全无此入口，无需 hideBuiltin 反向下线。
 *
 * 组件自包含：开关按钮渲染在顶栏槽内；日志面板经 Teleport 投放到
 * .message-area-wrapper 下（绝对定位于消息区右侧，不参与 flex 布局）。
 * 展开态由组件内部持有，按钮高亮与面板开合天然同步，不再走 WsBus 事件流转。
 *
 * 数据订阅：
 *   - 'AGENT_LOG' 信号，追加当前会话的子 Agent 日志；
 *   - 本地事件 'agent-log:clear'（切会话/新建会话）清空日志。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 依赖注入（均可选，缺失时降级）：
 *   wsBus        — WebSocket 消息总线
 *   sessionStore — 会话仓库（用于按当前会话过滤日志）
 *
 * 公开方法（通过 ref 调用）：
 *   toggle() / open() / close() / clearLogs()
 */
const AgentLogSidebar = {
    name: 'AgentLogSidebar',

    template: `
    <button class="sidebar-toggle-btn" @click="toggle"
            :title="visible ? '折叠 Agent Log' : '展开 Agent Log'"
            :style="visible ? {color: mainColor} : {}">
        <i data-lucide="activity" style="width: 18px; height: 18px;"></i>
    </button>
    <teleport v-if="anchorReady" to=".message-area-wrapper">
        <div class="agent-log-sidebar" :class="{ collapsed: !visible }">
            <div class="agent-log-header" :style="{ borderBottomColor: mainColor }">
                <div class="agent-log-header-title">
                    <i data-lucide="activity" style="width: 16px; height: 16px;"></i>
                    <span>Agent Log</span>
                    <span v-if="logs.length > 0" class="agent-log-badge">{{ logs.length }}</span>
                </div>
                <button class="agent-log-close-btn" @click="toggle" title="关闭面板">
                    <i data-lucide="x" style="width: 16px; height: 16px;"></i>
                </button>
            </div>
            <div class="agent-log-list" ref="logList">
                <div v-if="logs.length === 0" class="agent-log-empty">
                    <i data-lucide="scroll-text" style="width: 28px; height: 28px;"></i>
                    <span>暂无子 Agent 日志</span>
                    <span class="agent-log-empty-hint">执行 task_run 等子任务时将在此显示中间过程</span>
                </div>
                <div v-for="entry in logs" :key="entry.id" class="agent-log-entry"
                     :class="{ expanded: expandedId === entry.id }">
                    <div class="agent-log-entry-header" @click="toggleEntry(entry)">
                        <span class="agent-log-dot" :class="'dot-' + entry.phase"></span>
                        <span v-if="entry.iteration > 0" class="agent-log-iter">#{{ entry.iteration }}</span>
                        <span class="agent-log-title">{{ entry.title }}</span>
                        <span class="agent-log-time">{{ formatTime(entry.timestamp) }}</span>
                        <i v-if="entry.content" class="agent-log-expand-icon"
                           :class="{ rotated: expandedId === entry.id }"
                           data-lucide="chevron-down"
                           style="width: 12px; height: 12px;"></i>
                    </div>
                    <div v-if="entry.content && expandedId === entry.id" class="agent-log-entry-body">
                        <pre>{{ entry.content }}</pre>
                    </div>
                </div>
            </div>
        </div>
        <div class="agent-log-overlay" :class="{ visible: visible && isMobile }" @click="toggle"></div>
    </teleport>
    `,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        // 可选注入：插件页未提供 wsBus / sessionStore 时降级
        wsBus: { default: null },
        sessionStore: { default: null }
    },

    data: function () {
        return {
            logs: [],
            visible: false,
            expandedId: null,
            isMobile: window.innerWidth < 769,
            // Teleport 目标就绪标记：面板要投放到 .message-area-wrapper，
            // 初始 patch 时该元素可能尚未入文档，等 mounted（post-flush）再开启
            anchorReady: false
        };
    },

    watch: {
        logs: {
            handler: function () {
                this.$nextTick(() => {
                    this.scrollToBottom();
                });
            },
            deep: false
        }
    },

    mounted: function () {
        var self = this;
        this._onResize = function () {
            self.isMobile = window.innerWidth < 769;
        };
        window.addEventListener('resize', this._onResize);

        // 投放目标此时已在文档中（mounted 为 post-flush），开启 Teleport
        this.anchorReady = !!document.querySelector('.message-area-wrapper');

        // 订阅 Agent Log 信号与本地事件（wsBus 由 app.provide 注入）
        if (this.wsBus) {
            this._unsubAgentLog = this.wsBus.on('AGENT_LOG', (payload) => {
                try {
                    this.onAgentLog(JSON.parse(payload));
                } catch (e) {
                    console.error('解析 AGENT_LOG 失败:', e);
                }
            });
            this._unsubClear = this.wsBus.on('agent-log:clear', () => this.clearLogs());
        }
    },

    beforeUnmount: function () {
        if (this._onResize) {
            window.removeEventListener('resize', this._onResize);
        }
        if (this._unsubAgentLog) { this._unsubAgentLog(); this._unsubAgentLog = null; }
        if (this._unsubClear) { this._unsubClear(); this._unsubClear = null; }
    },

    methods: {
        /* ---- 公开方法 ---- */
        toggle: function () {
            this.visible = !this.visible;
        },

        open: function () {
            this.visible = true;
        },

        close: function () {
            this.visible = false;
        },

        clearLogs: function () {
            this.logs = [];
            this.expandedId = null;
        },

        /* ---- 内部 ---- */
        onAgentLog: function (entry) {
            if (!entry) return;
            // 只记录当前会话的日志；无 sessionStore（插件页）时不作过滤
            if (this.sessionStore) {
                var current = this.sessionStore.state.currentSession;
                var currentId = current ? current.id : undefined;
                if (entry.sessionId !== currentId) return;
            }
            this.logs.push(entry);
        },

        toggleEntry: function (entry) {
            if (!entry.content) return;
            this.expandedId = this.expandedId === entry.id ? null : entry.id;
        },

        scrollToBottom: function () {
            var el = this.$refs.logList;
            if (el) {
                el.scrollTop = el.scrollHeight;
            }
        },

        formatTime: function (ts) {
            if (!ts) return '';
            var d = new Date(ts);
            var h = String(d.getHours()).padStart(2, '0');
            var m = String(d.getMinutes()).padStart(2, '0');
            var s = String(d.getSeconds()).padStart(2, '0');
            return h + ':' + m + ':' + s;
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            var self = this;
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
