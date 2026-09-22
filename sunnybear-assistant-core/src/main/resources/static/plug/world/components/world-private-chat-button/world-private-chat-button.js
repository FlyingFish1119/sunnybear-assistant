/**
 * 私聊 / 独白按钮（发送区工具栏插件，锚点 'toolbar'）。
 *
 * 点击切换私聊浮条（浮条本体是 overlay 锚点的 WorldPrivateChatPanel）。
 * 状态读写 worldPage.privateChat。
 *
 * Props:
 *   mainColor — String
 *
 * Injects:
 *   worldPage — 页面级共享状态
 *
 * 依赖全局：lucide。
 */
const WorldPrivateChatButton = {
    name: 'WorldPrivateChatButton',

    template: `
    <button class="send-area-icon-btn world-attach-btn"
            :class="{ 'panel-open': visible }"
            @click="toggle"
            title="发起私聊 / 独白">
        <i data-lucide="message-square"></i>
    </button>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        worldPage: { required: true }
    },

    computed: {
        visible: function () { return this.worldPage.privateChat.visible; }
    },

    methods: {
        toggle: function () {
            const st = this.worldPage.privateChat;
            if (st.visible) {
                st.visible = false;
                st.form = { targets: [], content: '' };
            } else {
                st.mode = 'whisper';
                st.form = { targets: [], content: '' };
                // 展开前刷新角色列表，保证选项新鲜
                if (this.worldPage.refreshCharacters) this.worldPage.refreshCharacters();
                st.visible = true;
            }
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined' && this.$el) lucide.createIcons({ root: this.$el });
    }
};
