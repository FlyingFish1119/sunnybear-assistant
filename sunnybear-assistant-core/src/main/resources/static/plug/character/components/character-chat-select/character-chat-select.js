/**
 * 角色对话快捷选项面板（气泡尾插件，锚点 'message-bubble'）。
 *
 * 原为 character_index 消息气泡内联的「快捷选项」块；重写后作为插件组件，
 * 通过 MessageAreaPlugins.registerSlot('message-bubble', ...) 挂载到每条消息气泡末尾，
 * 组件内部自判「是否应渲染」（仅最后一条已完成、非流式的 assistant 消息）。
 *
 * msg.chatSelect 由页面 adapter 的 onServerMessage/onMessagesLoaded 钩子回填
 * （读 extension.chatSelect），本组件只负责展示与点击。
 *
 * Props:
 *   msg — Object  当前消息（由锚点注入）
 *
 * Injects:
 *   sessionStore       — 会话/消息仓库（判断最后一条 / 流式）
 *   messageAreaContext — 消息区共享上下文（取主色、发消息）
 */
const CharacterChatSelect = {
    name: 'CharacterChatSelect',

    template: `
    <div v-if="active" class="chat-select-panel" :style="{ '--cs-main': ctx.mainColor }">
        <div v-if="msg.chatSelect.title" class="chat-select-title">{{ msg.chatSelect.title }}</div>
        <button v-for="(opt, oi) in msg.chatSelect.options"
                :key="'cs-' + msg.id + '-' + oi"
                class="chat-select-option"
                @click.stop="onClick(opt)">
            {{ opt }}
        </button>
    </div>`,

    props: {
        msg: { type: Object, required: true }
    },

    inject: {
        sessionStore: { required: true },
        messageAreaContext: { required: true }
    },

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        /** 是否把该消息渲染成可点击的快捷选项（仅当它是最后一条、且已完成、非流式） */
        active: function () {
            var msg = this.msg;
            if (!msg || msg.role !== 'assistant') return false;
            if (!msg.id || String(msg.id).startsWith('streaming_')) return false;
            if (this.sessionStore.isStreaming) return false;
            if (!msg.chatSelect || !msg.chatSelect.options || !msg.chatSelect.options.length) return false;
            var list = this.sessionStore.currentMessages;
            var last = list && list.length ? list[list.length - 1] : null;
            return !!(last && last.id === msg.id);
        }
    },

    methods: {
        /** 点击选项：立即消失，并把内容作为下一条用户消息发送 */
        onClick: function (content) {
            if (!content) return;
            this.msg.chatSelect = null; // 选项消费后消失，防止重复点击
            this.sessionStore.sendMessage({ content: content, files: [], tts: false });
        }
    }
};
