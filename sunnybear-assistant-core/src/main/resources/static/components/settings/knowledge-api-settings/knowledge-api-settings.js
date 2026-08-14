/**
 * 知识库 API 设置组件
 *
 * 展示：知识库 API 条目（模型 / URL 摘要）
 * 修改：对话框内编辑模型、API URL、API Key
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const KnowledgeApiSettings = {
    name: 'KnowledgeApiSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="link" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">知识库 API</span>
                    <span class="settings-item-desc">{{ settings.model || '未配置' }}{{ settings.url ? ' · ' + settings.url : '' }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.model || '未配置' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.knowledgeapi" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="link" style="width:20px;height:20px"></i>
                    <span>知识库 API</span>
                </div>
            </template>
            <el-form :model="knowledgeApiForm" label-width="80px" label-position="left">
                <el-form-item label="模型">
                    <input class="settings-input" v-model="knowledgeApiForm.model" placeholder="例如: text-embedding-3-small">
                </el-form-item>
                <el-form-item label="API URL">
                    <input class="settings-input" v-model="knowledgeApiForm.url" placeholder="例如: https://api.openai.com/v1/embeddings">
                </el-form-item>
                <el-form-item label="API Key">
                    <input class="settings-input" v-model="knowledgeApiForm.apiKey" placeholder="输入 API Key">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.knowledgeapi = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveKnowledgeApi" :disabled="saving.knowledgeapi">
                        <span v-if="saving.knowledgeapi" class="btn-spinner"></span>
                        <span>{{ saving.knowledgeapi ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { knowledgeapi: false },
            knowledgeApiForm: { model: '', url: '', apiKey: '' }
        };
    },

    methods: {
        openDialog() {
            this.knowledgeApiForm = {
                model: this.settings.model || '',
                url: this.settings.url || '',
                apiKey: this.settings.apiKey || ''
            };
            this.dialogs.knowledgeapi = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveKnowledgeApi() {
            if (!this.knowledgeApiForm.model.trim()) {
                ElementPlus.ElMessage.warning('模型不能为空'); return;
            }
            if (!this.knowledgeApiForm.url.trim()) {
                ElementPlus.ElMessage.warning('API URL 不能为空'); return;
            }
            if (!this.knowledgeApiForm.apiKey.trim()) {
                ElementPlus.ElMessage.warning('API Key 不能为空'); return;
            }
            this.postSave('settings/knowledgeapi/save', {
                model: this.knowledgeApiForm.model.trim(),
                url: this.knowledgeApiForm.url.trim(),
                apiKey: this.knowledgeApiForm.apiKey.trim()
            }, 'knowledgeapi');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
