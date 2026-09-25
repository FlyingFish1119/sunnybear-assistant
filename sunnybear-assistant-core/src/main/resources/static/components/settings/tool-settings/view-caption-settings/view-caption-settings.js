/**
 * 图片/视频识别（view_caption_tool）设置组件
 *
 * 展示：识别条目（识别分辨率摘要）
 * 修改：条目右侧输入框直接编辑最大边长（分辨率，像素），失焦/回车即保存
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const ViewCaptionSettings = {
    name: 'ViewCaptionSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="image" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">图片识别</span>
                    <span class="settings-item-desc">识别时缩放的最大边长（分辨率）</span>
                </div>
            </div>
            <div class="settings-item-right">
                <input class="settings-inline-number" type="number" min="0" step="50"
                       v-model.number="currentMaxLength" :disabled="saving.viewcaption" @change="onMaxLengthChange">
                <span class="settings-item-value">px</span>
            </div>
        </div>
    </div>`,

    data() {
        return {
            currentMaxLength: this.settings.maxLength != null ? this.settings.maxLength : 500
        };
    },

    watch: {
        'settings.maxLength'(v) {
            this.currentMaxLength = v != null ? v : 500;
        }
    },

    methods: {
        async onMaxLengthChange() {
            const v = this.currentMaxLength;
            if (v == null || v === '' || isNaN(v) || v < 0) {
                ElementPlus.ElMessage.warning('最大边长不能为负数');
                this.currentMaxLength = this.settings.maxLength != null ? this.settings.maxLength : 500;
                return;
            }
            const ok = await this.postSave('settings/viewcaption/save', { maxLength: v }, 'viewcaption');
            if (!ok) this.currentMaxLength = this.settings.maxLength != null ? this.settings.maxLength : 500;
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
