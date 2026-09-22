/**
 * 角色数据库面板开关按钮（顶栏右栏插件，锚点 'topbar-right'）。
 *
 * 原为 character_index 顶栏内联的数据库按钮；重写后作为插件组件挂载。
 * 点击通过 WsBus 本地事件 'character-db:toggle' 通知页面里的 character-db-panel，
 * 并订阅 'character-db:visibility' 回显高亮（与 agent-log 同套路，避免父子耦合）。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * Injects:
 *   wsBus — WebSocket 消息总线（可选）
 */
const CharacterDbButton = {
    name: 'CharacterDbButton',

    template: `
    <button class="sidebar-toggle-btn" @click="toggle"
            :title="visible ? '收起数据库面板' : '展开数据库面板'"
            :style="visible ? {color: mainColor} : {}">
        <i data-lucide="database" style="width: 18px; height: 18px;"></i>
    </button>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        wsBus: { default: null }
    },

    data: function () {
        return {
            visible: false
        };
    },

    methods: {
        toggle: function () {
            if (this.wsBus) this.wsBus.emit('character-db:toggle');
        }
    },

    mounted: function () {
        if (this.wsBus) {
            this._unsub = this.wsBus.on('character-db:visibility', (val) => {
                this.visible = !!val;
            });
        }
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    beforeUnmount: function () {
        if (this._unsub) { this._unsub(); this._unsub = null; }
    }
};
