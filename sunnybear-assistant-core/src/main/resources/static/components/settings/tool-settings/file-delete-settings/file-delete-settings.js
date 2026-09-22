/**
 * 文件删除工具设置组件
 *
 * 展示：文件删除条目（权限策略摘要）
 * 修改：对话框内选择执行模式
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const FileDeleteSettings = {
    name: 'FileDeleteSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="trash-2" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">文件删除</span>
                    <span class="settings-item-desc">控制文件删除的权限策略</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getModeLabel(settings.mode) }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.filedelete" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="trash-2" style="width:20px;height:20px"></i>
                    <span>文件删除</span>
                </div>
            </template>
            <el-form :model="fileDeleteForm" label-width="80px" label-position="left">
                <el-form-item label="执行模式">
                    <el-select v-model="fileDeleteForm.mode" style="width:100%">
                        <el-option value="auto" label="自动 (auto)"></el-option>
                        <el-option value="alwaysAsked" label="始终询问 (alwaysAsked)"></el-option>
                        <el-option value="neverAsked" label="从不询问 (neverAsked)"></el-option>
                        <el-option value="alwaysRejectDanger" label="始终拒绝危险 (alwaysRejectDanger)"></el-option>
                    </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.filedelete = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveFileDelete" :disabled="saving.filedelete">
                        <span v-if="saving.filedelete" class="btn-spinner"></span>
                        <span>{{ saving.filedelete ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { filedelete: false },
            fileDeleteForm: { mode: 'auto' }
        };
    },

    methods: {
        openDialog() {
            this.fileDeleteForm = {
                mode: this.settings.mode || 'auto'
            };
            this.dialogs.filedelete = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveFileDelete() {
            const mode = this.fileDeleteForm.mode;
            if (!mode) { ElementPlus.ElMessage.warning('请选择执行模式'); return; }
            if (!['auto','alwaysAsked','neverAsked','alwaysRejectDanger'].includes(mode)) {
                ElementPlus.ElMessage.warning('无效的执行模式'); return;
            }
            this.postSave('settings/filedelete/save', { mode }, 'filedelete');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
