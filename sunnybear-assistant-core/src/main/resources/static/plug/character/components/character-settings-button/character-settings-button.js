/**
 * 角色设置按钮（侧边栏 footer 插件，锚点 'sidebar-footer'）。
 *
 * 角色页的设置不在总设置页，而是角色专属的 character_settings.html；
 * 故页面以同名槽 key 'settings' 覆盖核心默认设置按钮（无需 hideBuiltin），
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

    inject: {
        appSettings: { default: null },
        // 页面级共享状态（character_index.html 注入）：取当前角色 id 带入设置页
        characterPage: { default: null }
    },

    computed: {
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return 'lightsalmon';
        }
    },

    methods: {
        go: function () {
            var id = this.characterPage && this.characterPage.characterId;
            var url = API.BASE_PATH + 'plug/character/character_settings.html';
            if (id) {
                url += '?characterId=' + encodeURIComponent(id);
            }
            window.location.href = url;
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
