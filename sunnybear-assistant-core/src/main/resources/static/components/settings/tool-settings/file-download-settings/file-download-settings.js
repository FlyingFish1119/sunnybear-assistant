/**
 * 文件下载工具设置组件
 *
 * 展示：文件下载条目（权限策略摘要）
 * 修改：条目右侧下拉框直接选择执行模式，选择即保存
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
        <div class="settings-item">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="download" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">文件下载</span>
                    <span class="settings-item-desc">控制文件下载的权限策略</span>
                </div>
            </div>
            <div class="settings-item-right">
                <el-select :model-value="currentMode" size="small" style="width:150px"
                           :disabled="saving.filedownload" @change="onModeChange">
                    <el-option value="alwaysAsked" label="始终询问"></el-option>
                    <el-option value="neverAsked" label="从不询问"></el-option>
                </el-select>
            </div>
        </div>
    </div>`,

    data() {
        return {
            currentMode: this.settings.mode || 'alwaysAsked'
        };
    },

    watch: {
        'settings.mode'(mode) {
            this.currentMode = mode || 'alwaysAsked';
        }
    },

    methods: {
        async onModeChange(mode) {
            if (!['alwaysAsked','neverAsked'].includes(mode)) {
                ElementPlus.ElMessage.warning('无效的执行模式');
                this.currentMode = this.settings.mode || 'alwaysAsked';
                return;
            }
            const ok = await this.postSave('settings/filedownload/save', { mode }, 'filedownload');
            if (!ok) this.currentMode = this.settings.mode || 'alwaysAsked';
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
