/**
 * 文件编辑工具设置组件
 *
 * 展示：文件编辑条目（权限策略摘要）
 * 修改：对话框内选择执行模式
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const FileEditSettings = {
    name: 'FileEditSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="file-code" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">文件编辑</span>
                    <span class="settings-item-desc">控制文件编辑的权限策略</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getModeLabel(settings.mode) }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.fileedit" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="file-code" style="width:20px;height:20px"></i>
                    <span>文件编辑</span>
                </div>
            </template>
            <el-form :model="fileEditForm" label-width="80px" label-position="left">
                <el-form-item label="执行模式">
                    <select class="settings-select" v-model="fileEditForm.mode">
                        <option value="auto">自动 (auto)</option>
                        <option value="alwaysAsked">始终询问 (alwaysAsked)</option>
                        <option value="neverAsked">从不询问 (neverAsked)</option>
                        <option value="alwaysRejectDanger">始终拒绝危险 (alwaysRejectDanger)</option>
                    </select>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.fileedit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveFileEdit" :disabled="saving.fileedit">
                        <span v-if="saving.fileedit" class="btn-spinner"></span>
                        <span>{{ saving.fileedit ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { fileedit: false },
            fileEditForm: { mode: 'auto' }
        };
    },

    methods: {
        openDialog() {
            this.fileEditForm = {
                mode: this.settings.mode || 'auto'
            };
            this.dialogs.fileedit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveFileEdit() {
            const mode = this.fileEditForm.mode;
            if (!mode) { ElementPlus.ElMessage.warning('请选择执行模式'); return; }
            if (!['auto','alwaysAsked','neverAsked','alwaysRejectDanger'].includes(mode)) {
                ElementPlus.ElMessage.warning('无效的执行模式'); return;
            }
            this.postSave('settings/fileedit/save', { mode }, 'fileedit');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
