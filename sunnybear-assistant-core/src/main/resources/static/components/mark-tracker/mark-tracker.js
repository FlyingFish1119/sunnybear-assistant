/**
 * 步骤清单跟踪组件（自包含）
 *
 * 后端 mark_upsert_tool 每次把当前会话的完整步骤数组通过 ###MARK### + MarkPayload 推下来：
 *   { sessionId, marks: [ { id, content, status } ] }
 * status ∈ pending | in_progress | completed | cancelled。
 *
 * 本组件：
 *   - 收起时只显示"当前正在做"的那一条（in_progress，其次第一条 pending），带进度 n/m；
 *   - 点击展开浮层，列出全部步骤与状态；
 *   - 恢复：切换会话 / 刷新后从 sessionStore.currentSession.extension.chat_mark 读取，
 *     并按 sessionId 做本地缓存，避免内存里的会话对象过期；
 *   - 实时：订阅 WsBus 的 MARK 信号，只接受当前会话的推送。
 *
 * 图标不用 lucide：createIcons 会把 Vue 管理的 <i> 整个换成 <svg>，导致响应式失效
 * （图标停在第一次、旧节点删不掉）。这里用内联 SVG + v-html，完全交给 Vue 渲染。
 *
 * 无步骤（marks 为空）时整个入口按钮不渲染。
 *
 * Props:
 *   mainColor — String 主题色
 *
 * Injects:
 *   wsBus        — WebSocket 消息总线，订阅 ###MARK###
 *   sessionStore — 会话仓库，读取当前会话 id / extension
 */

/* 状态图标（路径取自 lucide），纯字符串，由 v-html 渲染 */
const MARK_ICON_BODIES = {
    circle: '<circle cx="12" cy="12" r="10"/>',
    loader: '<line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/>'
        + '<line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/>'
        + '<line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/>'
        + '<line x1="4.93" y1="19.07" x2="7.76" y2="16.24"/><line x1="16.24" y1="7.76" x2="19.07" y2="4.93"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    'check-check': '<path d="M18 6 7 17l-5-5"/><path d="m22 10-7.5 7.5L13 16"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    'chevron-up': '<path d="m18 15-6-6-6 6"/>'
};

/** 生成内联 SVG 字符串；loader 附带旋转动画类 */
function markIconSvg(name) {
    const body = MARK_ICON_BODIES[name] || MARK_ICON_BODIES.circle;
    const spin = name === 'loader' ? ' mk-spin' : '';
    return '<svg class="mk-svg' + spin + '" xmlns="http://www.w3.org/2000/svg"'
        + ' viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
        + ' stroke-linecap="round" stroke-linejoin="round">' + body + '</svg>';
}

const MarkTracker = {
    name: 'MarkTracker',

    template: `
    <div class="mark-tracker" :style="{'--main-color': mainColor}">
      <button v-if="visible"
              class="mark-tracker-toggle"
              :class="{ 'is-open': expanded }"
              @click.stop="toggle"
              :title="expanded ? '收起步骤清单' : '展开步骤清单'">
        <span class="mark-tracker-icon" v-html="svgOf(currentIcon)"></span>
        <span class="mark-tracker-label">{{ currentLabel }}</span>
        <span class="mark-tracker-progress">{{ doneCount }}/{{ total }}</span>
        <span class="mark-tracker-caret" :class="{ 'rotated': expanded }"
              v-html="svgOf('chevron-up')"></span>
      </button>

      <div v-if="visible && expanded" class="mark-tracker-panel" @click.stop>
        <div class="mark-tracker-head">
          <span>步骤清单</span>
          <span class="mark-tracker-head-progress">{{ doneCount }}/{{ total }}</span>
        </div>
        <ul class="mark-tracker-list">
          <li v-for="mark in marks"
              :key="mark.id"
              class="mark-tracker-item"
              :class="'is-' + mark.status">
            <span class="mark-tracker-item-icon" v-html="svgOf(iconOf(mark.status))"></span>
            <span class="mark-tracker-item-text">{{ mark.content }}</span>
          </li>
        </ul>
      </div>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        wsBus: { default: null },
        sessionStore: { default: null }
    },

    data() {
        return {
            expanded: false,
            // 当前会话的步骤数组
            marks: [],
            // 会话 id → marks 的本地缓存（切换会话 / 重连后快速恢复）
            cache: {}
        };
    },

    computed: {
        sessionId() {
            return this.sessionStore ? this.sessionStore.currentSessionId : undefined;
        },
        visible() {
            return this.marks.length > 0;
        },
        total() {
            return this.marks.length;
        },
        doneCount() {
            return this.marks.filter(m => m.status === 'completed').length;
        },
        // 当前聚焦：优先 in_progress，其次第一条 pending
        current() {
            return this.marks.find(m => m.status === 'in_progress')
                || this.marks.find(m => m.status === 'pending')
                || null;
        },
        currentLabel() {
            if (this.current) return this.current.content;
            return this.total > 0 && this.doneCount === this.total ? '全部完成' : '步骤清单';
        },
        currentIcon() {
            return this.current ? this.iconOf(this.current.status) : 'check-check';
        }
    },

    watch: {
        // 切换会话 → 重新加载该会话的清单
        sessionId() {
            this.expanded = false;
            this.syncFromSession();
        }
    },

    methods: {
        /** 图标名 → 内联 SVG 字符串（模板里用 v-html 渲染） */
        svgOf(name) {
            return markIconSvg(name);
        },

        iconOf(status) {
            switch (status) {
                case 'in_progress': return 'loader';
                case 'completed': return 'check';
                case 'cancelled': return 'x';
                default: return 'circle';
            }
        },

        /** 恢复当前会话的清单：优先本地缓存，其次会话 extension */
        syncFromSession() {
            const id = this.sessionId;
            if (!id) {
                this.marks = [];
                return;
            }
            if (this.cache[id]) {
                this.marks = this.cache[id].slice();
                return;
            }
            this.marks = this.parseFromSession();
        },

        /** 从 sessionStore.currentSession.extension.chat_mark 解析清单 */
        parseFromSession() {
            const session = this.sessionStore ? this.sessionStore.currentSession : null;
            if (!session || !session.extension) return [];
            try {
                const ext = typeof session.extension === 'string'
                    ? JSON.parse(session.extension)
                    : session.extension;
                const marks = ext ? ext['chat_mark'] : null;
                return Array.isArray(marks) ? marks : [];
            } catch (e) {
                console.error('解析会话 extension 中的步骤清单失败:', e);
                return [];
            }
        },

        /** ###MARK### 推送：只接受当前会话，写缓存并刷新显示 */
        applyMarks(payload) {
            if (!payload || payload.sessionId !== this.sessionId) return;
            const marks = Array.isArray(payload.marks) ? payload.marks : [];
            this.cache[payload.sessionId] = marks;
            this.marks = marks;
            if (marks.length === 0) {
                this.expanded = false;
            }
        },

        toggle() {
            this.expanded = !this.expanded;
        },

        onDocumentClick(e) {
            if (this.expanded && this.$el && !this.$el.contains(e.target)) {
                this.expanded = false;
            }
        }
    },

    mounted() {
        if (this.wsBus) {
            this._unsubMark = this.wsBus.on('MARK', (payload) => {
                try {
                    this.applyMarks(JSON.parse(payload));
                } catch (e) {
                    console.error('解析 MARK 失败:', e);
                }
            });
            // 重连后会话对象可能已刷新，重新同步一次
            this._unsubConnected = this.wsBus.on('ws:connected', () => this.syncFromSession());
        }
        document.addEventListener('click', this.onDocumentClick);
        this.syncFromSession();
    },

    beforeUnmount() {
        if (this._unsubMark) { this._unsubMark(); this._unsubMark = null; }
        if (this._unsubConnected) { this._unsubConnected(); this._unsubConnected = null; }
        document.removeEventListener('click', this.onDocumentClick);
    }
};
