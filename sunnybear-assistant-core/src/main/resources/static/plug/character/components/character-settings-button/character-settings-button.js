/**
 * 角色设置按钮（侧边栏 footer 插件，锚点 'sidebar-footer'）。
 *
 * 角色页的设置不在总设置页，而是角色专属的 character_settings.html；
 * 故页面隐藏核心的设置按钮（ChatSidebarPlugins.hideBuiltin('settings-button')），
 * 由本组件提供跳转目标，样式沿用核心 .sidebar-icon-btn。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 依赖全局：API、lucide。
 */
const CharacterSettingsButton = {
    name: 'CharacterSettingsButton',

    template: `
    <button class="sidebar-icon-btn" @click="go" title="角色设置">
        <i data-lucide="settings"></i>
    </button>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    methods: {
        go: function () {
            window.location.href = API.BASE_PATH + 'plug/character/character_settings.html';
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
