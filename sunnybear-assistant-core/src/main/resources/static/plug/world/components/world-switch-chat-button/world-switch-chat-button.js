/**
 * 发言权移交按钮（发送区工具栏插件，锚点 'toolbar'）。
 *
 * 点击切换移交浮条（浮条本体是 overlay 锚点的 WorldSwitchChatPanel）。
 *
 * Props:
 *   mainColor — String
 *
 * Injects:
 *   worldPage — 页面级共享状态
 *
 * 依赖全局：lucide。
 */
const WorldSwitchChatButton = {
    name: 'WorldSwitchChatButton',

    template: `
    <button class="send-area-icon-btn world-attach-btn"
            :class="{ 'panel-open': visible }"
            @click="toggle"
            title="发言权移交（switch）">
        <i data-lucide="arrow-right-left"></i>
    </button>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        worldPage: { required: true }
    },

    computed: {
        visible: function () { return this.worldPage.switchChat.visible; }
    },

    methods: {
        toggle: function () {
            const st = this.worldPage.switchChat;
            if (st.visible) {
                st.visible = false;
                st.form = { target: '' };
            } else {
                st.form = { target: '' };
                if (this.worldPage.refreshCharacters) this.worldPage.refreshCharacters();
                st.visible = true;
            }
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined' && this.$el) lucide.createIcons({ root: this.$el });
    }
};
