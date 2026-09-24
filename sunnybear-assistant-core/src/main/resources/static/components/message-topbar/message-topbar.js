/**
 * 顶部信息栏组件
 *
 * 展示：菜单（侧边栏开关）、模型名、会话名（可双击编辑）、待确认工具/提问入口、
 *       知识库命中闪现、连接状态指示器、Agent Log 开关。
 *
 * 组件自包含连接指示器（chat-connection，内部建立 WebSocket 并交接给 WsBus），
 * 并自行通过 WsBus 订阅 ###KNOWLEDGE_HIT### 信号触发知识命中闪现。
 *
 * 工具确认 / 结构化提问弹窗也内聚在本组件内（与展开入口同处），组件自行通过
 * ref 调用其 expand()，并监听 pending-change 维护入口角标数量。
 *
 * Agent Log 按钮通过 WsBus 本地事件与 agent-log-sidebar 解耦：
 * 点击时 emit 'agent-log:toggle'，并订阅 'agent-log:visibility' 回显按钮高亮。
 *
 * 模型展示文本由本组件自行计算：启动时拉取 chat / chat_pro 设置，
 * 结合注入 store 的 currentSession.enablePro 决定显示哪个模型。
 * 别处（如发送区模型切换）保存模型后经 wsBus 的 'settings:model-updated' 事件同步。
 * 插件页可通过 sessionStore.configure({ getModelDisplay }) 覆盖这段展示。
 *
 * 插件扩展：通过全局注册表 TopbarPlugins 按锚点插入扩展组件（左栏 / 右栏），
 * 见文件顶部 TopbarPlugins 说明。
 *
 * Props:
 *   mainColor             — String   主题色
 *   wsUrl                 — String   WebSocket 地址
 *
 * 菜单按钮通过 WsBus 本地事件通知 chat-sidebar 切换：
 * 点击时 emit 'sidebar:toggle'。
 *
 * 依赖注入（可选）：
 *   wsBus                  — WebSocket 消息总线
 *   sessionStore           — 会话/消息仓库（读取 currentSession.enablePro / 模型覆盖）
 */

/**
 * 顶栏插件注册表（全局单例）。
 *
 * 插件在任意时机调用 registerSlot 声明扩展组件，顶栏挂载时同步进内部槽并按锚点渲染。
 * 锚点：
 *   'topbar-left'   左栏（模型名 / 会话名一带）
 *   'topbar-right'  右栏（连接状态 / Agent Log 一带）
 * 组件会收到 prop: { mainColor }，可 inject sessionStore / wsBus。
 *
 * 还可通过 hideBuiltin(key) 隐藏内置元素（插件页不需要的按钮等）。
 * 内置元素 key：
 *   'menu'              侧边栏开关
 *   'model'             模型名标签
 *   'session-name'      会话名
 *   'ctx-gauge'         上下文用量环
 *   'pending-tool'      待确认工具入口
 *   'pending-question'  待回答提问入口
 *   'knowledge-flash'   知识库命中闪现
 *   'connection'        连接状态指示器
 *   'agent-log'         Agent Log 开关
 */
const TopbarPlugins = (function () {
    const slotsByAnchor = Object.create(null);
    /** 被隐藏的内置元素 key 集合 */
    const hiddenBuiltins = Object.create(null);

    function registerSlot(anchor, component, order) {
        if (!anchor || !component) return;
        if (!slotsByAnchor[anchor]) slotsByAnchor[anchor] = [];
        const arr = slotsByAnchor[anchor];
        arr.push({ component: component, order: order || 0 });
        arr.sort((a, b) => a.order - b.order);
    }

    /** 隐藏一个内置元素（key 见文件顶部说明） */
    function hideBuiltin(key) {
        if (key) hiddenBuiltins[key] = true;
    }

    return {
        registerSlot: registerSlot,
        hideBuiltin: hideBuiltin,
        /** 供组件挂载时取某锚点已登记槽的拷贝 */
        snapshot: function (anchor) {
            return (slotsByAnchor[anchor] || []).slice();
        },
        /** 供组件挂载时取一份被隐藏 key 的拷贝 */
        hiddenSnapshot: function () {
            return Object.assign({}, hiddenBuiltins);
        }
    };
})();

const MessageTopbar = {
    name: 'MessageTopbar',

    template: `
    <div class="message-area-top">
        <div style="display: flex; align-items: center; gap: 6px; min-width: 0;">
            <button v-if="!isHidden('menu')" class="sidebar-toggle-btn" @click="toggleSidebar" title="展开/收起侧边栏">
                <i data-lucide="menu" style="width: 18px; height: 18px;"></i>
            </button>
            <span v-if="!isHidden('model')" class="model-name-tag">{{ displayModel }} ·</span>
            <chat-session-name v-if="!isHidden('session-name')" :main-color="mainColor"></chat-session-name>
            <!-- 上下文用量环：显示离自动压缩还剩多少（已用比例越高越满） -->
            <el-tooltip v-if="!isHidden('ctx-gauge') && contextRatio !== null"
                        effect="light"
                        placement="bottom"
                        :show-after="80"
                        :content="contextTip">
                <div class="ctx-gauge" :class="contextLevel" :style="{'--main-color': mainColor}">
                    <svg viewBox="0 0 24 24" width="20" height="20">
                        <circle class="ctx-gauge-track" cx="12" cy="12" r="9"></circle>
                        <circle class="ctx-gauge-fill" cx="12" cy="12" r="9"
                                :stroke-dasharray="ctxCircumference"
                                :stroke-dashoffset="ctxDashOffset"
                                transform="rotate(-90 12 12)"></circle>
                    </svg>
                </div>
            </el-tooltip>
            <!-- 待确认工具请求入口：弹窗收起后从此处重新展开 -->
            <button v-if="!isHidden('pending-tool') && pendingToolCount > 0"
                    class="pending-entry-btn"
                    :style="{'--main-color': mainColor}"
                    @click="$refs.toolConfirm.expand()"
                    :title="pendingToolCount + ' 个工具请求待确认'">
                <i data-lucide="shield-alert"></i>
                <span class="pending-entry-badge">{{ pendingToolCount }}</span>
            </button>
            <!-- 待回答提问入口：弹窗收起后从此处重新展开 -->
            <button v-if="!isHidden('pending-question') && pendingQuestionCount > 0"
                    class="pending-entry-btn"
                    :style="{'--main-color': mainColor}"
                    @click="$refs.toolQuestion.expand()"
                    :title="pendingQuestionCount + ' 个提问待回答'">
                <i data-lucide="message-circle-question"></i>
                <span class="pending-entry-badge">{{ pendingQuestionCount }}</span>
            </button>
            <!-- 左栏插件槽（锚点 'topbar-left'，如角色名等） -->
            <component v-for="(slot, si) in leftSlots"
                       :key="'topbar-left-' + si"
                       :is="slot.component"
                       :main-color="mainColor"></component>
        </div>
        <div style="display: flex; align-items: center; gap: 16px;">
            <span v-if="!isHidden('knowledge-flash') && knowledgeFlashVisible" class="knowledge-hit-flash" title="已自动检索知识库内容">
                <i data-lucide="database"></i>
            </span>
            <chat-connection v-if="!isHidden('connection')" :ws-url="wsUrl"></chat-connection>
            <button v-if="!isHidden('agent-log')" class="sidebar-toggle-btn" @click="toggleAgentLog"
                    :title="agentLogVisible ? '折叠 Agent Log' : '展开 Agent Log'"
                    :style="agentLogVisible ? {color: mainColor} : {}">
                <i data-lucide="activity" style="width: 18px; height: 18px;"></i>
            </button>
            <!-- 右栏插件槽（锚点 'topbar-right'，如数据库面板按钮等） -->
            <component v-for="(slot, si) in rightSlots"
                       :key="'topbar-right-' + si"
                       :is="slot.component"
                       :main-color="mainColor"></component>
        </div>
    </div>

    <!-- 工具确认 / 结构化提问弹窗（与上方入口按钮内聚；overlay 为 fixed 定位，不受此处布局影响） -->
    <tool-confirm ref="toolConfirm"
                  :main-color="mainColor"
                  @pending-change="pendingToolCount = $event"></tool-confirm>
    <tool-question ref="toolQuestion"
                   :main-color="mainColor"
                   @pending-change="pendingQuestionCount = $event"></tool-question>`,

    props: {
        mainColor:      { type: String,  default: 'lightsalmon' },
        wsUrl:          { type: String,  default: '' },
        // 上下文压缩阈值（token，来自用户设置）；为空/无效则隐藏用量环
        contextTokenLimit: { type: [Number, String], default: null }
    },

    inject: {
        // 可选注入：未提供时降级
        wsBus: { default: null },
        sessionStore: { default: null }
    },

    data: function () {
        return {
            // 锚点插件槽（挂载时从全局注册表同步）
            leftSlots: TopbarPlugins.snapshot('topbar-left'),
            rightSlots: TopbarPlugins.snapshot('topbar-right'),
            // 被插件隐藏的内置元素 key 集合
            hiddenBuiltins: TopbarPlugins.hiddenSnapshot(),
            knowledgeFlashVisible: false,
            // Agent Log 展开态（由 sidebar 通过 'agent-log:visibility' 回显）
            agentLogVisible: false,
            // 顶部入口角标：待确认工具请求数 / 待回答提问数（由内聚的弹窗组件上报）
            pendingToolCount: 0,
            pendingQuestionCount: 0,
            // 模型展示：chat / chat_pro 设置（启动时拉取）
            chatModel: null,
            chatProModel: null,
            // 用量环周长（半径 9），用于 stroke-dasharray/offset
            ctxCircumference: 2 * Math.PI * 9
        };
    },

    computed: {
        // 当前会话启用 Pro → 显示 Pro 模型，否则显示普通模型；无会话时两者并列。
        // 插件页可通过 sessionStore.adapter.getModelDisplay 覆盖（如角色自带的模型名）。
        displayModel: function () {
            if (this.modelDisplayOverride != null) return this.modelDisplayOverride;
            var session = this.sessionStore ? this.sessionStore.state.currentSession : {};
            if (!session || !session.id) {
                return (this.chatModel || '?') + ' / ' + (this.chatProModel || '?');
            }
            if (session.enablePro) {
                return this.chatProModel || '?';
            }
            return this.chatModel || '?';
        },

        /** 插件对模型展示的覆盖值：由 adapter.getModelDisplay 提供，未提供则为 null */
        modelDisplayOverride: function () {
            var adapter = this.sessionStore && this.sessionStore.adapter;
            if (!adapter || typeof adapter.getModelDisplay !== 'function') return null;
            try {
                return adapter.getModelDisplay({
                    session: this.sessionStore.state.currentSession,
                    chatModel: this.chatModel,
                    chatProModel: this.chatProModel
                });
            } catch (e) {
                console.error('getModelDisplay 钩子出错:', e);
                return null;
            }
        },

        /** 压缩阈值（token）：非正数视为未配置 */
        contextLimit: function () {
            var v = Number(this.contextTokenLimit);
            return isFinite(v) && v > 0 ? v : null;
        },

        /**
         * 最近一次真实输入 token 数：取消息列表里最后一条带 extension.chat_usage.prompt_tokens
         * 的 assistant 消息（与后端触发压缩的判定口径一致）。
         */
        contextUsed: function () {
            var msgs = this.sessionStore ? this.sessionStore.state.currentMessages : null;
            if (!msgs || !msgs.length) return null;
            for (var i = msgs.length - 1; i >= 0; i--) {
                var m = msgs[i];
                if (m.role === 'assistant' && m.extension && m.extension.chat_usage) {
                    var p = m.extension.chat_usage.prompt_tokens;
                    if (p != null) return Number(p);
                }
            }
            return null;
        },

        /** 上下文占用比例 [0,1]；无数据时 null（隐藏用量环） */
        contextRatio: function () {
            if (!this.contextLimit || this.contextUsed == null) return null;
            return Math.min(1, Math.max(0, this.contextUsed / this.contextLimit));
        },

        /** 占用等级：接近阈值时变色警示 */
        contextLevel: function () {
            var r = this.contextRatio;
            if (r === null) return '';
            if (r >= 0.9) return 'is-danger';
            if (r >= 0.6) return 'is-warn';
            return '';
        },

        /** 环的描边偏移：按比例填充 */
        ctxDashOffset: function () {
            return this.ctxCircumference * (1 - (this.contextRatio || 0));
        },

        /** 悬浮提示：已用 / 总量 / 剩余 */
        contextTip: function () {
            if (this.contextUsed == null || !this.contextLimit) return '';
            var used = this.formatTokens(this.contextUsed);
            var limit = this.formatTokens(this.contextLimit);
            var left = this.formatTokens(Math.max(0, this.contextLimit - this.contextUsed));
            var percent = Math.round(this.contextRatio * 100);
            return '上下文已用 ' + percent + '%（' + used + ' / ' + limit + '），剩余 ' + left + '，达上限将自动压缩';
        }
    },

    methods: {
        /** 某内置元素是否被插件隐藏（key 见文件顶部 TopbarPlugins 说明） */
        isHidden: function (key) {
            return !!this.hiddenBuiltins[key];
        },

        /**
         * 知识库命中：闪现图标，约 5 秒后自动消失（与 CSS 动画时长一致）。
         * 由组件自行订阅 ###KNOWLEDGE_HIT### 信号触发（见 mounted）。
         */
        flashKnowledge: function () {
            this.knowledgeFlashVisible = true;
            clearTimeout(this._knowledgeFlashTimer);
            this._knowledgeFlashTimer = setTimeout(function () {
                this.knowledgeFlashVisible = false;
            }.bind(this), 5000);
        },

        /** 展开/收起侧边栏：通过本地事件通知 chat-sidebar */
        toggleSidebar: function () {
            if (this.wsBus) {
                this.wsBus.emit('sidebar:toggle');
            }
        },

        /** 切换 Agent Log：通过本地事件通知 agent-log-sidebar */
        toggleAgentLog: function () {
            if (this.wsBus) {
                this.wsBus.emit('agent-log:toggle');
            }
        },

        /** token 数字压缩：1234 -> 1.2k，1048576 -> 1.0M */
        formatTokens: function (n) {
            if (n == null || isNaN(n)) return '-';
            n = Number(n);
            if (n < 1000) return String(n);
            if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
            return (n / 1000000).toFixed(1) + 'M';
        },

        /** 拉取 chat / chat_pro 模型设置，用于 displayModel 展示 */
        fetchChatSettings: async function () {
            try {
                const [chatResult, chatProResult] = await Promise.all([
                    API.settings.chat.get(),
                    API.settings.chat_pro.get()
                ]);
                if (chatResult.status === 200) {
                    this.chatModel = chatResult.data.model;
                }
                if (chatProResult.status === 200) {
                    this.chatProModel = chatProResult.data.model;
                }
            } catch (error) {
                console.error('获取聊天设置失败:', error);
            }
        }
    },

    mounted: function () {
        this.fetchChatSettings();
        if (this.wsBus) {
            this._unsubAgentLogVisibility = this.wsBus.on('agent-log:visibility', (val) => {
                this.agentLogVisible = !!val;
            });
            // 自行订阅知识库命中信号（原由父组件兜底分发后经 ref 调用）
            this._unsubKnowledgeHit = this.wsBus.on('KNOWLEDGE_HIT', () => this.flashKnowledge());
            // 模型设置在别处（如发送区模型切换）保存后，同步顶栏展示的模型名
            this._unsubModelUpdated = this.wsBus.on('settings:model-updated', (payload) => {
                if (!payload) return;
                if (payload.type === 'chat_pro') {
                    this.chatProModel = payload.model;
                } else {
                    this.chatModel = payload.model;
                }
            });
        }
    },

    beforeUnmount: function () {
        clearTimeout(this._knowledgeFlashTimer);
        if (this._unsubAgentLogVisibility) {
            this._unsubAgentLogVisibility();
            this._unsubAgentLogVisibility = null;
        }
        if (this._unsubKnowledgeHit) {
            this._unsubKnowledgeHit();
            this._unsubKnowledgeHit = null;
        }
        if (this._unsubModelUpdated) {
            this._unsubModelUpdated();
            this._unsubModelUpdated = null;
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
