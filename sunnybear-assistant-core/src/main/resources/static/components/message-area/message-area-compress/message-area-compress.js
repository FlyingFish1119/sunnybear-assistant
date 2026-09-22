/**
 * 上下文压缩卡片组件
 *
 * 从 message-area 拆出：长对话压缩旧历史期间，在消息尾部顶替流式空占位，
 * 免得用户对着「等待回复」干等。
 *
 * 状态直接取自注入的 sessionStore（compressState 就挂在仓库上，
 * 父级不需要中转，也就不需要传 prop）。
 *
 * Props:
 *   mainColor  — String  主题色
 *
 * Injects:
 *   sessionStore — 会话/消息仓库；compressState: 'running' | 'done' | null
 *
 * 依赖全局：lucide。
 */
const MessageAreaCompress = {
    name: 'MessageAreaCompress',

    template: `
    <div v-if="compressState" :style="{'--main-color': mainColor}"
         class="msg-compress-card" :class="{ 'is-done': compressState === 'done' }">
        <i v-if="compressState === 'done'" class="msg-compress-icon" data-lucide="check"></i>
        <i v-else class="msg-compress-icon msg-compress-spin" data-lucide="loader-circle"></i>
        <span>
            {{ compressState === 'done'
                ? '上下文已压缩，对话即将继续'
                : '正在压缩上下文，生成衔接摘要…' }}
        </span>
    </div>
    `,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        sessionStore: { required: true }
    },

    computed: {
        // 当前会话的上下文压缩状态：'running' | 'done' | null
        compressState: function () {
            return this.sessionStore.compressState;
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        // 图标随 compressState 在 check / loader-circle 之间切换，得自己补刷
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
