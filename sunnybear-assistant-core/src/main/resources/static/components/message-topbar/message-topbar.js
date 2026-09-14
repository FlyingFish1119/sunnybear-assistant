/**
 * 顶部信息栏组件
 *
 * 展示：菜单（侧边栏开关）、模型名、会话名（可双击编辑）、待确认工具/提问入口、
 *       知识库命中闪现、连接状态指示器、Agent Log 开关。
 *
 * 组件自包含连接指示器（内部建立 WebSocket 并通过事件上抛），知识命中闪现由
 * 父组件在收到 ###KNOWLEDGE_HIT### 时通过 ref 调用 flashKnowledge()。
 *
 * Props:
 *   currentSession        — Object   当前会话对象
 *   mainColor             — String   主题色
 *   displayModel          — String   当前模型展示文本
 *   wsUrl                 — String   WebSocket 地址
 *   pendingToolCount      — Number   待确认工具请求数
 *   pendingQuestionCount  — Number   待回答提问数
 *   agentLogVisible       — Boolean  Agent Log 是否展开
 *
 * Emits:
 *   toggle-sidebar         — 点击菜单按钮
 *   toggle-agent-log       — 点击 Agent Log 按钮
 *   expand-tool-confirm    — 点击待确认工具入口
 *   expand-tool-question   — 点击待回答提问入口
 *   update-session-name    — 会话名保存成功，参数为新名称
 *   connected(ws)          — WebSocket 连接建立
 *   disconnected()         — WebSocket 连接断开
 *
 * 公开方法（通过 ref 调用）：
 *   flashKnowledge()       — 闪现知识库命中图标，5 秒后自动消失
 */
const MessageTopbar = {
    name: 'MessageTopbar',

    template: `
    <div class="message-area-top">
        <div style="display: flex; align-items: center; gap: 6px; min-width: 0;">
            <button class="sidebar-toggle-btn" @click="$emit('toggle-sidebar')" title="展开/收起侧边栏">
                <i data-lucide="menu" style="width: 18px; height: 18px;"></i>
            </button>
            <span class="model-name-tag">{{ displayModel }} ·</span>
            <session-name
                :current-session="currentSession"
                :main-color="mainColor"
                @update-session-name="n => $emit('update-session-name', n)">
            </session-name>
            <!-- 待确认工具请求入口：弹窗收起后从此处重新展开 -->
            <button v-if="pendingToolCount > 0"
                    class="pending-entry-btn"
                    :style="{'--main-color': mainColor}"
                    @click="$emit('expand-tool-confirm')"
                    :title="pendingToolCount + ' 个工具请求待确认'">
                <i data-lucide="shield-alert"></i>
                <span class="pending-entry-badge">{{ pendingToolCount }}</span>
            </button>
            <!-- 待回答提问入口：弹窗收起后从此处重新展开 -->
            <button v-if="pendingQuestionCount > 0"
                    class="pending-entry-btn"
                    :style="{'--main-color': mainColor}"
                    @click="$emit('expand-tool-question')"
                    :title="pendingQuestionCount + ' 个提问待回答'">
                <i data-lucide="message-circle-question"></i>
                <span class="pending-entry-badge">{{ pendingQuestionCount }}</span>
            </button>
        </div>
        <div style="display: flex; align-items: center; gap: 16px;">
            <span v-if="knowledgeFlashVisible" class="knowledge-hit-flash" title="已自动检索知识库内容">
                <i data-lucide="database"></i>
            </span>
            <connection-indicator
                :ws-url="wsUrl"
                @connected="ws => $emit('connected', ws)"
                @disconnected="$emit('disconnected')">
            </connection-indicator>
            <button class="sidebar-toggle-btn" @click="$emit('toggle-agent-log')"
                    :title="agentLogVisible ? '折叠 Agent Log' : '展开 Agent Log'"
                    :style="agentLogVisible ? {color: mainColor} : {}">
                <i data-lucide="terminal" style="width: 18px; height: 18px;"></i>
            </button>
        </div>
    </div>`,

    props: {
        currentSession:       { type: Object,  default: function () { return {}; } },
        mainColor:            { type: String,  default: 'lightsalmon' },
        displayModel:         { type: String,  default: '' },
        wsUrl:                { type: String,  default: '' },
        pendingToolCount:     { type: Number,  default: 0 },
        pendingQuestionCount: { type: Number,  default: 0 },
        agentLogVisible:      { type: Boolean, default: false }
    },

    emits: [
        'toggle-sidebar',
        'toggle-agent-log',
        'expand-tool-confirm',
        'expand-tool-question',
        'update-session-name',
        'connected',
        'disconnected'
    ],

    data: function () {
        return {
            knowledgeFlashVisible: false
        };
    },

    methods: {
        /**
         * 知识库命中：闪现图标，约 5 秒后自动消失（与 CSS 动画时长一致）。
         * 由父组件在收到 ###KNOWLEDGE_HIT### 时通过 ref 调用。
         */
        flashKnowledge: function () {
            this.knowledgeFlashVisible = true;
            clearTimeout(this._knowledgeFlashTimer);
            this._knowledgeFlashTimer = setTimeout(function () {
                this.knowledgeFlashVisible = false;
            }.bind(this), 5000);
        }
    },

    beforeUnmount: function () {
        clearTimeout(this._knowledgeFlashTimer);
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
