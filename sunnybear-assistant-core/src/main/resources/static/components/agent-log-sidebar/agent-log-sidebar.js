/**
 * Agent Log 浮窗组件 — 展示子 Agent 中间执行日志
 *
 * 浮动在消息区右侧，不参与 flex 布局，不挤压消息面板。
 *
 * Props:
 *   logs      — Array<AgentLogEntry>  日志列表
 *   visible   — Boolean               是否展开
 *   mainColor — String                主题色
 *
 * Emits:
 *   toggle   — 用户点击关闭时触发
 */
const AgentLogSidebar = {
    name: 'AgentLogSidebar',

    template: `
    <div class="agent-log-sidebar" :class="{ collapsed: !visible }">
        <div class="agent-log-header" :style="{ borderBottomColor: mainColor }">
            <div class="agent-log-header-title">
                <i data-lucide="terminal" style="width: 16px; height: 16px;"></i>
                <span>Agent Log</span>
                <span v-if="logs.length > 0" class="agent-log-badge">{{ logs.length }}</span>
            </div>
            <button class="agent-log-close-btn" @click="$emit('toggle')" title="关闭面板">
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
    <div class="agent-log-overlay" :class="{ visible: visible && isMobile }" @click="$emit('toggle')"></div>
    `,

    props: {
        logs: { type: Array, default: () => [] },
        visible: { type: Boolean, default: false },
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['toggle'],

    data: function () {
        return {
            expandedId: null,
            isMobile: window.innerWidth < 769
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
    },

    beforeUnmount: function () {
        if (this._onResize) {
            window.removeEventListener('resize', this._onResize);
        }
    },

    methods: {
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
