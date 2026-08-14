/**
 * AI 模型设置组件
 *
 * 展示：对话模型 / 高级对话模型 / 小熊崽模型 / OCR / 任务模型 / TaskAI 六个条目
 * 修改：共享对话框编辑各类型的适配器、模型、流式/思考、高级参数（可折叠）
 * System Prompt 仅对话模型（chat）可配置；其余类型由系统内置固定
 * 适配器列表在组件挂载时自行加载
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const AiSettings = {
    name: 'AiSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog('chat')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="message-circle" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">对话模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('chat') }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('chat') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>
        <div class="settings-item" @click="openDialog('chat_pro')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="zap" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">高级对话模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('chat_pro') }} · 提示词复用对话模型</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('chat_pro') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>
        <div class="settings-item" @click="openDialog('cub')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="paw-print" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">小熊崽模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('cub') }} · 最轻量任务（如标题生成）</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('cub') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>
        <div class="settings-item" @click="openDialog('ocr')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="scan-eye" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">OCR 模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('ocr') }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('ocr') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>
        <div class="settings-item" @click="openDialog('mission')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="target" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">任务模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('mission') }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('mission') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>
        <div class="settings-item" @click="openDialog('task')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="list-checks" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">TaskAI 模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('task') }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('task') }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.ai" title="" width="760px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="cpu" style="width:20px;height:20px"></i>
                    <span>{{ aiDialogTitle }}</span>
                </div>
            </template>
            <el-form :model="aiForm" label-width="110px" label-position="left">
                <div class="form-group-title">基础配置</div>
                <el-form-item label="适配器名称">
                    <select class="settings-select" v-model="aiForm.adapterName">
                        <option value="" disabled>请选择适配器</option>
                        <option v-for="name in adapterList" :key="name" :value="name">{{ name }}</option>
                    </select>
                </el-form-item>
                <el-form-item label="模型">
                    <input class="settings-input" v-model="aiForm.model" placeholder="例如: gpt-4o, qwen-plus">
                </el-form-item>
                <el-form-item label="System Prompt" v-if="aiDialogType === 'chat'">
                    <textarea class="settings-textarea" v-model="aiForm.prompt" rows="3" placeholder="系统提示词（可选）"></textarea>
                </el-form-item>
                <el-form-item label="流式输出">
                    <el-switch v-model="aiForm.stream" active-text="开启" inactive-text="关闭"></el-switch>
                </el-form-item>
                <el-form-item label="思考模式">
                    <el-switch v-model="aiForm.thinking" active-text="开启" inactive-text="关闭"></el-switch>
                </el-form-item>

                <!-- 高级参数（可折叠） -->
                <div class="form-group-title" @click="toggleAiAdvanced" style="cursor:pointer;user-select:none;display:flex;align-items:center">
                    <span>高级参数</span>
                    <span style="font-size:12px;color:#c0c4cc;margin-left:4px">温度、Top P、惩罚等</span>
                    <i data-lucide="chevron-down" style="width:14px;height:14px;margin-left:auto;transition:transform 0.25s"
                       :style="{ transform: showAiAdvanced ? 'rotate(180deg)' : '' }"></i>
                </div>
                <div class="advanced-params" :class="{ expanded: showAiAdvanced }">
                    <div class="advanced-params-inner">
                        <el-form-item label="温度 (Temperature)">
                            <el-slider v-model="aiForm.temperature" :min="0" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </el-form-item>
                        <el-form-item label="Top P">
                            <el-slider v-model="aiForm.top_p" :min="0" :max="1" :step="0.05" show-input :format-tooltip="v => v.toFixed(2)" style="width: calc(100% - 110px)"></el-slider>
                        </el-form-item>
                        <el-form-item label="最大 Token 数">
                            <input class="settings-input-number" type="number" v-model.number="aiForm.maxTokens" min="1" max="8192" step="256">
                        </el-form-item>
                        <el-form-item label="频率惩罚">
                            <el-slider v-model="aiForm.frequencyPenalty" :min="-2" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </el-form-item>
                        <el-form-item label="存在惩罚">
                            <el-slider v-model="aiForm.presencePenalty" :min="-2" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </el-form-item>
                        <el-form-item label="推理深度">
                            <el-slider v-model="aiForm.reasoningEffort" :min="0" :max="2" :step="1" show-stops :marks="{0:'低',1:'高',2:'最深'}" :format-tooltip="v => ['低 (low)','高 (high)','最深 (max)'][v]" style="width: calc(100% - 110px)"></el-slider>
                        </el-form-item>
                    </div>
                </div>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.ai = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveAi" :disabled="saving.ai">
                        <span v-if="saving.ai" class="btn-spinner"></span>
                        <span>{{ saving.ai ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { ai: false },
            // 当前 AI 对话框的类型 (chat/chat_pro/cub/ocr/mission/task)
            aiDialogType: 'chat',
            showAiAdvanced: false,
            aiForm: { prompt: '', adapterName: '', model: '', stream: false, thinking: false, reasoningEffort: null, temperature: 1, top_p: 1, maxTokens: 4096, frequencyPenalty: 0, presencePenalty: 0 },
            // 可用适配器列表
            adapterList: []
        };
    },

    computed: {
        aiDialogTitle() {
            return AI_TYPE_NAMES[this.aiDialogType] || 'AI 模型';
        }
    },

    methods: {
        getAiSummary(type) {
            const s = this.settings[type] || {};
            return (s.adapterName || '?') + ' / ' + (s.model || '?') + (s.stream ? ' · 流式' : '');
        },
        getAiShort(type) {
            const s = this.settings[type] || {};
            return s.model || '未配置';
        },

        openDialog(type) {
            this.aiDialogType = type;
            const ai = this.settings[type] || {};
            this.aiForm = {
                prompt: ai.prompt || '',
                adapterName: ai.adapterName || '',
                model: ai.model || '',
                stream: ai.stream == null ? false : ai.stream,
                thinking: ai.thinking == null ? false : ai.thinking,
                reasoningEffort: ai.reasoningEffort != null ? ['low','high','max'].indexOf(ai.reasoningEffort) : null,
                temperature: ai.temperature != null ? ai.temperature : null,
                top_p: ai.top_p != null ? ai.top_p : null,
                maxTokens: ai.maxTokens != null ? ai.maxTokens : null,
                frequencyPenalty: ai.frequencyPenalty != null ? ai.frequencyPenalty : null,
                presencePenalty: ai.presencePenalty != null ? ai.presencePenalty : null
            };
            // 已有高级参数值则自动展开并填充默认值
            const hasAdvanced = ai.temperature != null || ai.top_p != null || ai.maxTokens != null || ai.frequencyPenalty != null || ai.presencePenalty != null || ai.reasoningEffort != null;
            this.showAiAdvanced = hasAdvanced;
            if (hasAdvanced) {
                const f = this.aiForm;
                if (f.temperature == null) f.temperature = 1;
                if (f.top_p == null) f.top_p = 1;
                if (f.maxTokens == null) f.maxTokens = 4096;
                if (f.frequencyPenalty == null) f.frequencyPenalty = 0;
                if (f.presencePenalty == null) f.presencePenalty = 0;
                if (f.reasoningEffort == null) f.reasoningEffort = 1;
            }
            this.dialogs.ai = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveAi() {
            if (!this.aiForm.adapterName.trim()) {
                ElementPlus.ElMessage.warning('适配器名称不能为空');
                return;
            }
            if (!this.aiForm.model.trim()) {
                ElementPlus.ElMessage.warning('模型名称不能为空');
                return;
            }
            const body = {
                // 仅对话模型可配置提示词；其余类型提示词由系统内置固定，保存时清空
                prompt: this.aiDialogType === 'chat' ? this.aiForm.prompt : '',
                adapterName: this.aiForm.adapterName.trim(),
                model: this.aiForm.model.trim(),
                stream: this.aiForm.stream,
                thinking: this.aiForm.thinking,
                reasoningEffort: this.showAiAdvanced && this.aiForm.reasoningEffort != null ? ['low','high','max'][this.aiForm.reasoningEffort] : null,
                // 未展开高级参数 → 置 null，让 API 使用默认值
                temperature: this.showAiAdvanced ? this.aiForm.temperature : null,
                top_p: this.showAiAdvanced ? this.aiForm.top_p : null,
                maxTokens: this.showAiAdvanced ? this.aiForm.maxTokens : null,
                frequencyPenalty: this.showAiAdvanced ? this.aiForm.frequencyPenalty : null,
                presencePenalty: this.showAiAdvanced ? this.aiForm.presencePenalty : null
            };
            this.postSave('settings/' + this.aiDialogType + '/save', body, 'ai');
        },

        toggleAiAdvanced() {
            if (!this.showAiAdvanced) {
                // 展开时，null 值填充默认值
                const f = this.aiForm;
                if (f.temperature == null) f.temperature = 1;
                if (f.top_p == null) f.top_p = 1;
                if (f.maxTokens == null) f.maxTokens = 4096;
                if (f.frequencyPenalty == null) f.frequencyPenalty = 0;
                if (f.presencePenalty == null) f.presencePenalty = 0;
                if (f.reasoningEffort == null) f.reasoningEffort = 1;
            }
            this.showAiAdvanced = !this.showAiAdvanced;
        },

        clearAiAdvanced() {
            this.aiForm.temperature = null;
            this.aiForm.top_p = null;
            this.aiForm.maxTokens = null;
            this.aiForm.frequencyPenalty = null;
            this.aiForm.presencePenalty = null;
            this.aiForm.reasoningEffort = null;
            this.showAiAdvanced = false;
            ElementPlus.ElMessage.success('高级参数已清除，保存后将使用 API 默认值');
        },

        /* ---------- 适配器列表 ---------- */
        async fetchAdapterList() {
            try {
                const r = await API.get('settings/adapters/list');
                if (r.status === 200) {
                    this.adapterList = r.data || [];
                }
            } catch (e) {
                console.error(e);
            }
        }
    },

    mounted() {
        this.fetchAdapterList();
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
