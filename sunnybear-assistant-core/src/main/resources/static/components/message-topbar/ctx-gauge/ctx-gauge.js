/**
 * 上下文用量环 — 显示离自动压缩还剩多少（已用比例越高越满）
 *
 * 以「插件」形态存在：核心页 index.html 通过
 *   TopbarPlugins.registerSlot('topbar-left', CtxGauge, 0)
 * 挂进顶栏左栏；插件页不注册即无此环，无需 hideBuiltin。
 *
 * 自包含：注入 sessionStore 取最近一次真实输入 token，注入 userSettings 取
 * contextTokenLimit（未配置则整环不渲染）。达到阈值前按比例填充，接近时变色警示。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 依赖注入（可选）：
 *   sessionStore — 会话/消息仓库；未提供时不显示
 *   userSettings — 用户设置（读 contextTokenLimit）；未提供时不显示
 */
const CtxGauge = {
    name: 'CtxGauge',

    template: `
    <el-tooltip v-if="ratio !== null"
                effect="light"
                placement="bottom"
                :show-after="80"
                :content="tip">
        <div class="ctx-gauge" :class="level" :style="{'--main-color': mainColor}">
            <svg viewBox="0 0 24 24" width="20" height="20">
                <circle class="ctx-gauge-track" cx="12" cy="12" r="9"></circle>
                <circle class="ctx-gauge-fill" cx="12" cy="12" r="9"
                        :stroke-dasharray="circumference"
                        :stroke-dashoffset="dashOffset"
                        transform="rotate(-90 12 12)"></circle>
            </svg>
        </div>
    </el-tooltip>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        sessionStore: { default: null },
        userSettings: { default: null }
    },

    data: function () {
        return {
            // 用量环周长（半径 9），用于 stroke-dasharray/offset
            circumference: 2 * Math.PI * 9
        };
    },

    computed: {
        /** 压缩阈值：取注入的用户设置，非正数视为未配置 */
        limit: function () {
            var v = Number(this.userSettings && this.userSettings.contextTokenLimit);
            return isFinite(v) && v > 0 ? v : null;
        },

        /**
         * 最近一次真实输入 token 数：取消息列表里最后一条带 extension.chat_usage.prompt_tokens
         * 的 assistant 消息（与后端触发压缩的判定口径一致）。
         */
        used: function () {
            var msgs = this.sessionStore ? this.sessionStore.state.currentMessages : null;
            if (!msgs || !msgs.length) return null;
            for (var i = msgs.length - 1; i >= 0; i--) {
                var m = msgs[i];
                if (m.role === 'assistant' && m.extension && m.extension.chat_usage) {
                    var p = m.extension.chat_usage.prompt_tokens;
                    if (p != null) return Number(p);
                }
            }
            return null;
        },

        /** 上下文占用比例 [0,1]；无数据时 null（隐藏用量环） */
        ratio: function () {
            if (!this.limit || this.used == null) return null;
            return Math.min(1, Math.max(0, this.used / this.limit));
        },

        /** 占用等级：接近阈值时变色警示 */
        level: function () {
            var r = this.ratio;
            if (r === null) return '';
            if (r >= 0.9) return 'is-danger';
            if (r >= 0.6) return 'is-warn';
            return '';
        },

        /** 环的描边偏移：按比例填充 */
        dashOffset: function () {
            return this.circumference * (1 - (this.ratio || 0));
        },

        /** 悬浮提示：已用 / 总量 / 剩余 */
        tip: function () {
            if (this.used == null || !this.limit) return '';
            var used = this.formatTokens(this.used);
            var limit = this.formatTokens(this.limit);
            var left = this.formatTokens(Math.max(0, this.limit - this.used));
            var percent = Math.round(this.ratio * 100);
            return '上下文已用 ' + percent + '%（' + used + ' / ' + limit + '），剩余 ' + left + '，达上限将自动压缩';
        }
    },

    methods: {
        /** token 数字压缩：1234 -> 1.2k，1048576 -> 1.0M */
        formatTokens: function (n) {
            if (n == null || isNaN(n)) return '-';
            n = Number(n);
            if (n < 1000) return String(n);
            if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
            return (n / 1000000).toFixed(1) + 'M';
        }
    }
};
