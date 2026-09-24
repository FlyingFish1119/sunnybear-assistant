/**
 * 知识库命中闪现 — 顶栏即时提示
 *
 * 以「插件」形态存在：核心页 index.html 通过
 *   TopbarPlugins.registerSlot('topbar-right', KnowledgeFlash, -10)
 * 挂进顶栏右栏；插件页不注册即无此提示，无需 hideBuiltin。
 *
 * 自包含：订阅 WsBus 的 KNOWLEDGE_HIT 信号，闪现图标约 5 秒后自动消失
 * （与 knowledge-flash.css 动画时长一致）。
 *
 * 依赖注入（可选）：
 *   wsBus — 未提供时静默不显示
 */
const KnowledgeFlash = {
    name: 'KnowledgeFlash',

    template: `
    <span v-if="visible" class="knowledge-hit-flash" title="已自动检索知识库内容">
        <i data-lucide="database"></i>
    </span>`,

    // 槽契约要求：TopbarPlugins 会向每个槽组件传 mainColor（此处样式走全局变量，未直接使用）
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

    mounted: function () {
        if (this.wsBus) {
            this._unsubKnowledgeHit = this.wsBus.on('KNOWLEDGE_HIT', () => this.flash());
        }
    },

    beforeUnmount: function () {
        clearTimeout(this._flashTimer);
        if (this._unsubKnowledgeHit) {
            this._unsubKnowledgeHit();
            this._unsubKnowledgeHit = null;
        }
    },

    methods: {
        flash: function () {
            this.visible = true;
            clearTimeout(this._flashTimer);
            this._flashTimer = setTimeout(() => {
                this.visible = false;
            }, 5000);
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
