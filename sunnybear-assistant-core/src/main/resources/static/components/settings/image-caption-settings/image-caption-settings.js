/**
 * 图片识别工具设置组件
 *
 * 展示：图片识别条目（识别分辨率摘要）
 * 修改：对话框内编辑最大边长（分辨率，像素）
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const ImageCaptionSettings = {
    name: 'ImageCaptionSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="image" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">图片识别</span>
                    <span class="settings-item-desc">识别时缩放的最大边长（分辨率）</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.maxLength || '-' }}px</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.imagecaption" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="image" style="width:20px;height:20px"></i>
                    <span>图片识别</span>
                </div>
            </template>
            <el-form :model="imageCaptionForm" label-width="130px" label-position="left">
                <el-form-item label="最大边长 (像素)">
                    <input class="settings-input-number" type="number" v-model.number="imageCaptionForm.maxLength" min="0" step="50">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.imagecaption = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveImageCaption" :disabled="saving.imagecaption">
                        <span v-if="saving.imagecaption" class="btn-spinner"></span>
                        <span>{{ saving.imagecaption ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { imagecaption: false },
            imageCaptionForm: { maxLength: 500 }
        };
    },

    methods: {
        openDialog() {
            this.imageCaptionForm = {
                maxLength: this.settings.maxLength != null ? this.settings.maxLength : 500
            };
            this.dialogs.imagecaption = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveImageCaption() {
            if (this.imageCaptionForm.maxLength == null || this.imageCaptionForm.maxLength < 0) {
                ElementPlus.ElMessage.warning('最大边长不能为负数'); return;
            }
            this.postSave('settings/imagecaption/save', {
                maxLength: this.imageCaptionForm.maxLength
            }, 'imagecaption');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
