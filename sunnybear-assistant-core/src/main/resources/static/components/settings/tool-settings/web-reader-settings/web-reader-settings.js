/**
 * 网页阅读工具设置组件
 *
 * 展示：网页阅读条目（浏览器超时摘要）
 * 修改：对话框内编辑无头浏览器超时
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
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="globe" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">网页阅读</span>
                    <span class="settings-item-desc">无头浏览器超时: {{ settings.browserTimeoutMs ? (settings.browserTimeoutMs / 1000) + 's' : '-' }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.browserTimeoutMs ? (settings.browserTimeoutMs / 1000) + 's' : '-' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.webreadertool" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="globe" style="width:20px;height:20px"></i>
                    <span>网页阅读</span>
                </div>
            </template>
            <el-form :model="webReaderForm" label-width="160px" label-position="left">
                <div class="form-group-title">无头浏览器</div>
                <el-form-item label="浏览器超时 (毫秒)">
                    <input class="settings-input-number" type="number" v-model.number="webReaderForm.browserTimeoutMs" min="1000" step="1000">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.webreadertool = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveWebReader" :disabled="saving.webreadertool">
                        <span v-if="saving.webreadertool" class="btn-spinner"></span>
                        <span>{{ saving.webreadertool ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { webreadertool: false },
            webReaderForm: { browserTimeoutMs: 30000 }
        };
    },

    methods: {
        openDialog() {
            this.webReaderForm = {
                browserTimeoutMs: this.settings.browserTimeoutMs != null ? this.settings.browserTimeoutMs : 30000
            };
            this.dialogs.webreadertool = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveWebReader() {
            if (this.webReaderForm.browserTimeoutMs == null || this.webReaderForm.browserTimeoutMs < 1000) {
                ElementPlus.ElMessage.warning('浏览器超时至少为 1000ms'); return;
            }
            this.postSave('settings/webreadertool/save', {
                browserTimeoutMs: this.webReaderForm.browserTimeoutMs
            }, 'webreadertool');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
