/**
 * 命令执行工具设置组件
 *
 * 展示：命令执行条目（模式/超时摘要）
 * 修改：对话框内编辑执行模式、超时、安全/最大输出大小、白名单、黑名单
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const CommandSettings = {
    name: 'CommandSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="terminal" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">命令执行</span>
                    <span class="settings-item-desc">模式: {{ settings.mode || 'auto' }}，超时: {{ settings.timeout || '-' }}s</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getModeLabel(settings.mode) }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.command" title="" width="760px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="terminal" style="width:20px;height:20px"></i>
                    <span>命令执行</span>
                </div>
            </template>
            <el-form :model="commandForm" label-width="110px" label-position="left">
                <el-form-item label="执行模式">
                    <el-select v-model="commandForm.mode" style="width:100%">
                        <el-option value="auto" label="🔧 自动 — 白名单免检，黑名单强制询问"></el-option>
                        <el-option value="alwaysAsked" label="❓ 始终询问 — 每次执行都需用户确认"></el-option>
                        <el-option value="alwaysRejectDanger" label="🛡 始终拒绝危险 — 自动拒绝危险命令"></el-option>
                        <el-option value="neverAsked" label="⚡ 从不询问 — 直接执行不询问"></el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="超时 (秒)">
                    <input class="settings-input-number" type="number" v-model.number="commandForm.timeout" min="1" max="3600">
                </el-form-item>
                <el-form-item label="安全输出大小 (B)">
                    <input class="settings-input-number" type="number" v-model.number="commandForm.safetyOutputSize" min="0" step="1024">
                </el-form-item>
                <el-form-item label="最大输出大小 (B)">
                    <input class="settings-input-number" type="number" v-model.number="commandForm.maxOutputSize" min="0" step="1024">
                </el-form-item>
                <el-form-item label="白名单">
                    <textarea class="settings-textarea" v-model="commandForm.whiteListStr" rows="3" placeholder="每行一个命令，命中则跳过危险检测"></textarea>
                </el-form-item>
                <el-form-item label="黑名单">
                    <textarea class="settings-textarea" v-model="commandForm.blackListStr" rows="3" placeholder="每行一个命令，命中则强制询问用户"></textarea>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.command = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveCommand" :disabled="saving.command">
                        <span v-if="saving.command" class="btn-spinner"></span>
                        <span>{{ saving.command ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { command: false },
            commandForm: { mode: 'auto', timeout: 10, safetyOutputSize: 8192, maxOutputSize: 32768, whiteListStr: '', blackListStr: '' }
        };
    },

    methods: {
        openDialog() {
            this.commandForm = {
                mode: this.settings.mode || 'auto',
                timeout: this.settings.timeout != null ? this.settings.timeout : 10,
                safetyOutputSize: this.settings.safetyOutputSize != null ? this.settings.safetyOutputSize : 8192,
                maxOutputSize: this.settings.maxOutputSize != null ? this.settings.maxOutputSize : 32768,
                whiteListStr: (this.settings.whiteList || []).join('\n'),
                blackListStr: (this.settings.blackList || []).join('\n')
            };
            this.dialogs.command = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveCommand() {
            const mode = this.commandForm.mode;
            if (!mode) { ElementPlus.ElMessage.warning('请选择执行模式'); return; }
            if (!['auto','alwaysAsked','alwaysRejectDanger','neverAsked'].includes(mode)) {
                ElementPlus.ElMessage.warning('无效的执行模式'); return;
            }
            if (this.commandForm.timeout == null || this.commandForm.timeout < 0) {
                ElementPlus.ElMessage.warning('超时时间不能为负数'); return;
            }
            if (this.commandForm.safetyOutputSize == null || this.commandForm.safetyOutputSize < 0) {
                ElementPlus.ElMessage.warning('安全输出大小不能为负数'); return;
            }
            if (this.commandForm.maxOutputSize == null || this.commandForm.maxOutputSize < 0) {
                ElementPlus.ElMessage.warning('最大输出大小不能为负数'); return;
            }
            const body = {
                mode: mode,
                timeout: this.commandForm.timeout,
                safetyOutputSize: this.commandForm.safetyOutputSize,
                maxOutputSize: this.commandForm.maxOutputSize,
                whiteList: (this.commandForm.whiteListStr || '').split('\n').map(s => s.trim()).filter(Boolean),
                blackList: (this.commandForm.blackListStr || '').split('\n').map(s => s.trim()).filter(Boolean)
            };
            this.postSave('settings/command/save', body, 'command');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
