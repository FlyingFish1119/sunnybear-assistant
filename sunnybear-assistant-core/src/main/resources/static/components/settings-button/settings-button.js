/**
 * 核心默认设置按钮（侧边栏 footer 默认槽组件，锚点 'sidebar-footer'，key 'settings'）。
 *
 * 由 chat-sidebar.js 在加载时以 key 'settings' 注册进 footer 槽。各页面如需要
 * 跳转到专属设置页（角色页 character_settings.html / 世界页 world_settings.html），
 * 用同名 key 注册自己的按钮即可「覆盖」本组件，无需再调用
 * ChatSidebarPlugins.hideBuiltin('settings-button')。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 依赖全局：API、lucide。
 */
const SettingsButton = {
    name: 'SettingsButton',

    template: `
    <button class="sidebar-icon-btn" @click="go" title="设置">
        <i data-lucide="settings"></i>
    </button>`,

    inject: {
        appSettings: { default: null }
    },

    computed: {
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return 'lightsalmon';
        }
    },

    methods: {
        go: function () {
            window.location.href = API.BASE_PATH + 'settings.html';
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
