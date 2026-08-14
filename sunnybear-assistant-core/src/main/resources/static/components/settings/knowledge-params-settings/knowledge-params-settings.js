/**
 * 知识库参数设置组件
 *
 * 展示：知识库参数条目（启用状态 / 相似度阈值摘要）
 * 修改：对话框内切换启用、调整相似度阈值
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const KnowledgeParamsSettings = {
    name: 'KnowledgeParamsSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="sliders-horizontal" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">知识库 参数</span>
                    <span class="settings-item-desc">{{ settings.enable ? '已启用' : '已禁用' }} · 阈值: {{ settings.similarityThreshold || '-' }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.enable ? '开启' : '关闭' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.knowledgesettings" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="database" style="width:20px;height:20px"></i>
                    <span>知识库 设置</span>
                </div>
            </template>
            <el-form :model="knowledgeForm" label-width="130px" label-position="left">
                <el-form-item label="启用">
                    <el-switch v-model="knowledgeForm.enable" active-text="开启" inactive-text="关闭"></el-switch>
                </el-form-item>
                <el-form-item label="相似度阈值">
                    <el-slider v-model="knowledgeForm.similarityThreshold" :min="0" :max="1" :step="0.01" show-input :format-tooltip="v => v.toFixed(2)" style="width: calc(100% - 130px)"></el-slider>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.knowledgesettings = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveKnowledge" :disabled="saving.knowledgesettings">
                        <span v-if="saving.knowledgesettings" class="btn-spinner"></span>
                        <span>{{ saving.knowledgesettings ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { knowledgesettings: false },
            knowledgeForm: { enable: false, similarityThreshold: 0.7 }
        };
    },

    methods: {
        openDialog() {
            this.knowledgeForm = {
                enable: this.settings.enable != null ? this.settings.enable : false,
                similarityThreshold: this.settings.similarityThreshold != null ? this.settings.similarityThreshold : 0.7
            };
            this.dialogs.knowledgesettings = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveKnowledge() {
            if (this.knowledgeForm.similarityThreshold == null || this.knowledgeForm.similarityThreshold < 0) {
                ElementPlus.ElMessage.warning('相似度阈值不能为负数'); return;
            }
            this.postSave('settings/knowledge/save', {
                enable: this.knowledgeForm.enable,
                similarityThreshold: this.knowledgeForm.similarityThreshold
            }, 'knowledgesettings');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
