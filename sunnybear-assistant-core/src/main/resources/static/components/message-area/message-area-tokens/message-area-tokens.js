/**
 * 本轮 token 消耗徽章组件
 *
 * 从 message-area 拆出：气泡操作按钮行右端的「tokens 1.2k」徽章，
 * 悬浮展开输入 / 输出 / 缓存 / 思考 / 合计的明细卡片。
 *
 * 数字压缩统一走 TokenFormat.short（utils/token-format.js），
 * 本组件不再自带一份 —— 那正是之前到处复制的老毛病。
 *
 * Props:
 *   msg — Object  消息对象；用量取自 msg.extension.chat_usage，没有就不渲染
 *
 * 依赖全局：TokenFormat (utils/token-format.js)、ElementPlus (el-tooltip)、lucide。
 */
const MessageAreaTokens = {
    name: 'MessageAreaTokens',

    template: `
    <div v-if="usage" class="msg-tokens">
        <el-tooltip effect="light" placement="top" :show-after="100"
                    popper-class="msg-tokens-tooltip">
            <template #content>
                <div class="msg-tokens-tip">
                    <div v-for="row in rows" :key="row.label" class="msg-tokens-tip-row">
                        <span class="msg-tokens-tip-label">{{ row.label }}</span>
                        <span class="msg-tokens-tip-value">{{ row.value }}</span>
                    </div>
                </div>
            </template>
            <span class="msg-tokens-badge">
                <i data-lucide="coins" style="width:12px;height:12px"></i>
                tokens {{ summary }}
            </span>
        </el-tooltip>
    </div>
    `,

    props: {
        msg: { type: Object, required: true }
    },

    computed: {
        /** 本轮用量；没有就返回 null，模板据此不渲染（父级不用判断） */
        usage: function () {
            return (this.msg && this.msg.extension && this.msg.extension.chat_usage) || null;
        },

        /** 徽章上的紧凑摘要：优先总 token，缺失时退回输入 token */
        summary: function () {
            if (!this.usage) return '';
            var value = this.usage.total_tokens != null
                ? this.usage.total_tokens
                : this.usage.prompt_tokens;
            return TokenFormat.short(value);
        },

        /** 悬浮明细行；缺哪项就不列哪项 */
        rows: function () {
            var usage = this.usage;
            if (!usage) return [];
            var rows = [];
            var add = function (label, value) {
                if (value != null) {
                    rows.push({ label: label, value: TokenFormat.short(value) });
                }
            };
            add('输入', usage.prompt_tokens);
            add('输出', usage.completion_tokens);
            if (usage.cached_tokens != null) add('缓存输入', usage.cached_tokens);
            if (usage.reasoning_tokens != null) add('思考', usage.reasoning_tokens);
            add('合计', usage.total_tokens);
            return rows;
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        // usage 由无到有时根元素才出现，图标得自己补刷
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
