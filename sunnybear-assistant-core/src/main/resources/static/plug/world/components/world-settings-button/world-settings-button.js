/**
 * 世界观设置按钮（侧边栏 footer 插件，锚点 'sidebar-footer'）。
 *
 * 跳转世界专属的 world_settings.html；页面隐藏核心设置按钮后由本组件提供。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 依赖全局：API、lucide。
 */
const WorldSettingsButton = {
    name: 'WorldSettingsButton',

    template: `
    <button class="sidebar-icon-btn" @click="go" title="世界观设置">
        <i data-lucide="settings"></i>
    </button>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    methods: {
        go: function () {
            window.location.href = API.BASE_PATH + 'plug/world/world_settings.html';
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
