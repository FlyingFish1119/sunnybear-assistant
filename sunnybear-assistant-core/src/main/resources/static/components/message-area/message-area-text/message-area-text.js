/**
 * 消息正文文本块渲染组件。
 *
 * 一个 text 类型 content 的渲染统一入口：
 *   · 默认：按 Markdown 渲染（流式期间走按块渲染，避免整段重建）；
 *   · 插件注册了正文渲染器时：委托给插件组件，允许其对文本做自定义分段/
 *     结构化渲染（如世界观页的 <private> 私聊标签分段）。
 *
 * 插件通过 MessageAreaPlugins.registerTextRenderer(component) 注册渲染器；
 * 渲染器组件契约：
 *   props: { content, msg, streaming }
 *   inject: messageAreaContext（可选）
 *
 * Props:
 *   content   — Object   content 对象（{ type:'text', content }）
 *   msg       — Object   所属消息
 *   streaming — Boolean  是否为流式消息（决定是否按块渲染）
 *
 * Injects:
 *   messageAreaContext — 消息区共享上下文（mermaidNonce 等）
 *
 * 依赖全局：$md。
 */
const MessageAreaText = {
    name: 'MessageAreaText',

    inject: {
        messageAreaContext: { required: true }
    },

    template: `
    <!-- 插件自定义渲染器优先 -->
    <component v-if="renderer"
               :is="renderer"
               :content="content"
               :msg="msg"
               :streaming="streaming"></component>
    <!-- 默认：流式按块渲染（已闭合块不重建） -->
    <div v-else-if="streaming" class="markdown-body md-streaming">
        <div v-for="(block, bi) in $md.streamBlocks(text)" :key="bi" class="md-block" v-html="block.html"></div>
    </div>
    <!-- 默认：整段 Markdown -->
    <div v-else class="markdown-body" v-html="html"></div>`,

    props: {
        content: { type: Object, required: true },
        msg: { type: Object, default: null },
        streaming: { type: Boolean, default: false }
    },

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        /** 插件注册的正文渲染器（可能为 null） */
        renderer: function () {
            return MessageAreaPlugins.getTextRenderer();
        },
        text: function () {
            return this.content ? (this.content.content || '') : '';
        },
        /** 整段 Markdown（缓存到 content 对象的 text 槽：历史消息只解析一次） */
        html: function () {
            void this.ctx.mermaidNonce; // mermaid 异步出图后触发重渲染
            return memoMsgHtml(this.content, 'text', this.text, this.$md.render);
        }
    }
};
