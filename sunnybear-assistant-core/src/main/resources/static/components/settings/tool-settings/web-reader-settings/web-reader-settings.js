/**
 * 网页阅读工具设置组件
 *
 * 展示：网页阅读条目（浏览器超时摘要）
 * 修改：条目右侧输入框直接编辑无头浏览器超时（秒），失焦/回车即保存（存库为毫秒）
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const WebReaderSettings = {
    name: 'WebReaderSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="globe" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">网页阅读</span>
                    <span class="settings-item-desc">无头浏览器超时（秒）</span>
                </div>
            </div>
            <div class="settings-item-right">
                <input class="settings-inline-number" type="number" min="1" step="1"
                       v-model.number="currentTimeoutSec" :disabled="saving.webreadertool" @change="onTimeoutChange">
                <span class="settings-item-value">秒</span>
            </div>
        </div>
    </div>`,

    data() {
        return {
            currentTimeoutSec: this.toSec(this.settings.browserTimeoutMs)
        };
    },

    watch: {
        'settings.browserTimeoutMs'(v) {
            this.currentTimeoutSec = this.toSec(v);
        }
    },

    methods: {
        toSec(ms) {
            return ms ? ms / 1000 : 30;
        },

        async onTimeoutChange() {
            const s = this.currentTimeoutSec;
            if (s == null || s === '' || isNaN(s) || s < 1) {
                ElementPlus.ElMessage.warning('浏览器超时至少为 1 秒');
                this.currentTimeoutSec = this.toSec(this.settings.browserTimeoutMs);
                return;
            }
            const ok = await this.postSave('settings/webreadertool/save', {
                browserTimeoutMs: Math.round(s * 1000)
            }, 'webreadertool');
            if (!ok) this.currentTimeoutSec = this.toSec(this.settings.browserTimeoutMs);
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
