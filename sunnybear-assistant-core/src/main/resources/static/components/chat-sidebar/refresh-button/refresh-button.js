/**
 * 核心默认刷新按钮（侧边栏 footer 默认槽组件，锚点 'sidebar-footer'，key 'refresh'）。
 *
 * 由 chat-sidebar.js 在加载时以 key 'refresh' 注册进 footer 槽，与核心默认设置按钮
 * （key 'settings'）并列。点击即刷新会话列表，刷新中图标旋转并禁用按钮；各页面如需
 * 替换刷新行为，用同名 key 注册自己的按钮即可「覆盖」本组件，无需 hideBuiltin。
 *
 * 刷新优先级：ChatSidebarPlugins 已注册的 provider（提供 refresh 时）→ 注入的
 * sessionStore.refreshSessions()。两者皆无则空操作。
 *
 * Props:
 *   mainColor — String  主题色（槽组件由侧边栏透传，缺省时回退注入的 appSettings）
 *
 * 依赖全局：ChatSidebarPlugins、lucide。
 */
const RefreshButton = {
    name: 'RefreshButton',

    template: `
    <button class="sidebar-icon-btn"
            :disabled="refreshing"
            :title="refreshing ? '刷新中…' : '刷新会话列表'"
            @click="refresh">
        <i data-lucide="refresh-cw" :class="{ 'is-spinning': refreshing }"></i>
    </button>`,

    inject: {
        appSettings: { default: null },
        sessionStore: { default: null }
    },

    data: function () {
        return {
            /** 是否正在刷新（刷新中禁用按钮并旋转图标） */
            refreshing: false
        };
    },

    computed: {
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return 'lightsalmon';
        }
    },

    methods: {
        /**
         * 刷新会话列表：插件 provider 优先，回退 sessionStore。
         */
        refresh: function () {
            if (this.refreshing) return;
            var self = this;
            var provider = (typeof ChatSidebarPlugins !== 'undefined')
                ? ChatSidebarPlugins.getProvider() : null;
            var task;
            if (provider && typeof provider.refresh === 'function') {
                task = provider.refresh();
            } else if (this.sessionStore) {
                task = this.sessionStore.refreshSessions();
            } else {
                task = Promise.resolve();
            }
            this.refreshing = true;
            Promise.resolve(task)
                .catch(function (error) {
                    console.error('刷新会话列表失败:', error);
                })
                .finally(function () {
                    self.refreshing = false;
                });
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
