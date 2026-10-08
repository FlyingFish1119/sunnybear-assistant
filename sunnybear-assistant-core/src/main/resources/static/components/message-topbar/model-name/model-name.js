/**
 * 顶栏模型名标签（topbar-left 默认槽，key 'model'）
 *
 * 以「槽组件」形态默认注册：message-topbar.js 启动时以 key 'model' 挂入左栏。
 * 插件页不需要时用 TopbarPlugins.removeSlot('topbar-left', 'model') 移除，无需 hideBuiltin。
 *
 * 展示文本：启动时拉取 chat / chat_pro 设置，结合注入 store 的 currentSession.enablePro
 * 决定显示哪个模型；别处（发送区模型切换）保存后经 wsBus 'settings:model-updated' 同步。
 * 插件页可通过 sessionStore.adapter.getModelDisplay 覆盖这段展示。
 *
 * Props:
 *   mainColor — String  主题色（样式走全局变量，此处不直接使用）
 *
 * Injects:
 *   wsBus        — 消息总线（订阅 settings:model-updated）
 *   sessionStore — 会话仓库（读 currentSession.enablePro / 模型覆盖）
 */
const TopbarModel = {
    name: 'TopbarModel',

    template: `<span class="model-name-tag">{{ displayModel }} ·</span>`,

    inject: {
        wsBus: { default: null },
        sessionStore: { default: null }
    },

    data: function () {
        return {
            // 模型展示：chat / chat_pro 设置（启动时拉取）
            chatModel: null,
            chatProModel: null
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
        }
    },

    methods: {
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
        if (this._unsubModelUpdated) {
            this._unsubModelUpdated();
            this._unsubModelUpdated = null;
        }
    }
};
