/**
 * 文件下载工具设置组件
 *
 * 展示：文件下载条目（权限策略摘要）
 * 修改：对话框内选择执行模式（仅始终询问 / 从不询问）
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const FileDownloadSettings = {
    name: 'FileDownloadSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="download" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">文件下载</span>
                    <span class="settings-item-desc">控制文件下载的权限策略</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getModeLabel(settings.mode) }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.filedownload" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="download" style="width:20px;height:20px"></i>
                    <span>文件下载</span>
                </div>
            </template>
            <el-form :model="fileDownloadForm" label-width="80px" label-position="left">
                <el-form-item label="执行模式">
                    <select class="settings-select" v-model="fileDownloadForm.mode">
                        <option value="alwaysAsked">始终询问 (alwaysAsked)</option>
                        <option value="neverAsked">从不询问 (neverAsked)</option>
                    </select>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.filedownload = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveFileDownload" :disabled="saving.filedownload">
                        <span v-if="saving.filedownload" class="btn-spinner"></span>
                        <span>{{ saving.filedownload ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { filedownload: false },
            fileDownloadForm: { mode: 'alwaysAsked' }
        };
    },

    methods: {
        openDialog() {
            this.fileDownloadForm = {
                mode: this.settings.mode || 'alwaysAsked'
            };
            this.dialogs.filedownload = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveFileDownload() {
            const mode = this.fileDownloadForm.mode;
            if (!mode) { ElementPlus.ElMessage.warning('请选择执行模式'); return; }
            if (!['alwaysAsked','neverAsked'].includes(mode)) {
                ElementPlus.ElMessage.warning('无效的执行模式'); return;
            }
            this.postSave('settings/filedownload/save', { mode }, 'filedownload');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
