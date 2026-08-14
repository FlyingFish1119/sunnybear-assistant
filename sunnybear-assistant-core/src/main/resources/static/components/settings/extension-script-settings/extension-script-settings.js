/**
 * 扩展脚本工具设置组件
 *
 * 展示：扩展脚本条目（超时/最大输出摘要）
 * 修改：对话框内编辑超时、最大输出大小
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const ExtensionScriptSettings = {
    name: 'ExtensionScriptSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="puzzle" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">扩展脚本</span>
                    <span class="settings-item-desc">超时: {{ settings.timeout || '-' }}s · 最大输出: {{ formatSize(settings.maxOutputSize) }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.timeout || '-' }}s</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.extensionscript" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="puzzle" style="width:20px;height:20px"></i>
                    <span>扩展脚本</span>
                </div>
            </template>
            <el-form :model="extensionScriptForm" label-width="130px" label-position="left">
                <el-form-item label="超时 (秒)">
                    <input class="settings-input-number" type="number" v-model.number="extensionScriptForm.timeout" min="1" max="3600">
                </el-form-item>
                <el-form-item label="最大输出大小 (B)">
                    <input class="settings-input-number" type="number" v-model.number="extensionScriptForm.maxOutputSize" min="0" step="1024">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.extensionscript = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveExtensionScript" :disabled="saving.extensionscript">
                        <span v-if="saving.extensionscript" class="btn-spinner"></span>
                        <span>{{ saving.extensionscript ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { extensionscript: false },
            extensionScriptForm: { timeout: 30, maxOutputSize: 65536 }
        };
    },

    methods: {
        openDialog() {
            this.extensionScriptForm = {
                timeout: this.settings.timeout != null ? this.settings.timeout : 30,
                maxOutputSize: this.settings.maxOutputSize != null ? this.settings.maxOutputSize : 65536
            };
            this.dialogs.extensionscript = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveExtensionScript() {
            if (this.extensionScriptForm.timeout == null || this.extensionScriptForm.timeout < 1) {
                ElementPlus.ElMessage.warning('超时时间至少为 1 秒'); return;
            }
            if (this.extensionScriptForm.maxOutputSize == null || this.extensionScriptForm.maxOutputSize < 0) {
                ElementPlus.ElMessage.warning('最大输出大小不能为负数'); return;
            }
            this.postSave('settings/extensionscript/save', {
                timeout: this.extensionScriptForm.timeout,
                maxOutputSize: this.extensionScriptForm.maxOutputSize
            }, 'extensionscript');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
