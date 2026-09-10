/** 高级参数展开 / 恢复时使用的编辑器默认值（reasoningEffort 存的是滑块下标） */
const AI_ADVANCED_DEFAULTS = {
    temperature: 1,
    top_p: 1,
    maxTokens: 4096,
    frequencyPenalty: 0,
    presencePenalty: 0,
    reasoningEffort: 1
};

/**
 * 高级参数行
 *
 * 标题右侧常驻「清空」按钮，把该项置为 null；值为 null 时控件替换为「未设置」占位 + 「恢复」按钮。
 * 对应约定：null = 未设置，保存时不写入请求体，由 API 使用默认值。
 */
const AiParamRow = {
    name: 'AiParamRow',

    props: {
        label: { type: String, required: true },
        // true = 当前值为 null（未设置）；此时不渲染默认插槽里的控件
        unset: { type: Boolean, default: false }
    },

    emits: ['clear', 'restore'],

    template: `
    <el-form-item label-width="200px">
        <template #label>
            <span class="ai-param-label">
                <span>{{ label }}</span>
                <button v-if="!unset" type="button" class="ai-param-clear-btn"
                        title="清空该项，保存后使用 API 默认值" @click.stop="$emit('clear')">清空</button>
            </span>
        </template>
        <div v-if="unset" class="ai-param-unset">
            <span class="ai-param-unset-text">未设置 · 保存后使用 API 默认值</span>
            <button type="button" class="ai-param-clear-btn" title="恢复为默认值"
                    @click.stop="$emit('restore')">恢复</button>
        </div>
        <slot v-else></slot>
    </el-form-item>`
};

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
                    <el-select v-model="aiForm.adapterName" placeholder="请选择适配器" style="width:100%">
                        <el-option v-for="name in adapterList" :key="name" :label="name" :value="name"></el-option>
                    </el-select>
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
                        <ai-param-row label="温度 (Temperature)" :unset="aiForm.temperature == null"
                                      @clear="aiForm.temperature = null" @restore="aiForm.temperature = 1">
                            <el-slider v-model="aiForm.temperature" :min="0" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </ai-param-row>
                        <ai-param-row label="Top P" :unset="aiForm.top_p == null"
                                      @clear="aiForm.top_p = null" @restore="aiForm.top_p = 1">
                            <el-slider v-model="aiForm.top_p" :min="0" :max="1" :step="0.05" show-input :format-tooltip="v => v.toFixed(2)" style="width: calc(100% - 110px)"></el-slider>
                        </ai-param-row>
                        <ai-param-row label="最大 Token 数" :unset="aiForm.maxTokens == null"
                                      @clear="aiForm.maxTokens = null" @restore="aiForm.maxTokens = 4096">
                            <input class="settings-input-number" type="number" v-model.number="aiForm.maxTokens" min="1" max="8192" step="256">
                        </ai-param-row>
                        <ai-param-row label="频率惩罚" :unset="aiForm.frequencyPenalty == null"
                                      @clear="aiForm.frequencyPenalty = null" @restore="aiForm.frequencyPenalty = 0">
                            <el-slider v-model="aiForm.frequencyPenalty" :min="-2" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </ai-param-row>
                        <ai-param-row label="存在惩罚" :unset="aiForm.presencePenalty == null"
                                      @clear="aiForm.presencePenalty = null" @restore="aiForm.presencePenalty = 0">
                            <el-slider v-model="aiForm.presencePenalty" :min="-2" :max="2" :step="0.1" show-input :format-tooltip="v => v.toFixed(1)" style="width: calc(100% - 110px)"></el-slider>
                        </ai-param-row>
                        <ai-param-row class="ai-param-with-marks" label="推理深度" :unset="aiForm.reasoningEffort == null"
                                      @clear="aiForm.reasoningEffort = null" @restore="aiForm.reasoningEffort = 1">
                            <el-slider v-model="aiForm.reasoningEffort" :min="0" :max="2" :step="1" show-stops :marks="{0:'低',1:'高',2:'最深'}" :format-tooltip="v => ['低 (low)','高 (high)','最深 (max)'][v]" style="width: calc(100% - 110px)"></el-slider>
                        </ai-param-row>
                    </div>
                </div>

                <!-- 自定义请求字段（厂商扩展） -->
                <div class="form-group-title" style="display:flex;align-items:center">
                    <span>自定义请求字段</span>
                    <span style="font-size:12px;color:#c0c4cc;margin-left:4px">厂商扩展参数，原样展开到请求体顶层</span>
                </div>
                <el-form-item>
                    <textarea class="settings-textarea" v-model="aiForm.customFieldsText" rows="4" spellcheck="false"
                              style="font-family:Consolas,Menlo,monospace;font-size:12px"
                              placeholder='JSON 对象，例如 {"metadata":{"type":"conversation"},"max_completion_tokens":8000}'></textarea>
                    <div style="font-size:12px;color:#909399;line-height:1.6;margin-top:4px">
                        留空或 {} = 不传额外字段；与内置参数（温度 / 最大 token / top_p / 频率惩罚等）同名的 key 会被忽略；必须是合法 JSON 对象。
                    </div>
                </el-form-item>
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
            aiForm: { prompt: '', adapterName: '', model: '', stream: false, thinking: false, reasoningEffort: null, temperature: 1, top_p: 1, maxTokens: 4096, frequencyPenalty: 0, presencePenalty: 0, customFieldsText: '' },
            // 可用适配器列表
            adapterList: []
        };
    },

    computed: {
        aiDialogTitle() {
            return AI_TYPE_NAMES[this.aiDialogType] || 'AI 模型';
        },

        /** 高级参数是否已配置（任一非 null）；全 null 即「未配置」，打开对话框时默认收起 */
        hasAiAdvanced() {
            return Object.keys(AI_ADVANCED_DEFAULTS).some(key => this.aiForm[key] != null);
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
                presencePenalty: ai.presencePenalty != null ? ai.presencePenalty : null,
                customFieldsText: (ai.customFields && Object.keys(ai.customFields).length) ? JSON.stringify(ai.customFields, null, 2) : ''
            };
            // 全部为 null 表示「未配置」，默认收起；有任意一项已配置则以展开态展示
            this.showAiAdvanced = this.hasAiAdvanced;
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
            // 解析自定义请求字段：空文本 = {}；否则必须是合法 JSON 对象
            let customFields = {};
            const customRaw = (this.aiForm.customFieldsText || '').trim();
            if (customRaw) {
                let parsed;
                try {
                    parsed = JSON.parse(customRaw);
                } catch (e) {
                    ElementPlus.ElMessage.error('自定义请求字段不是合法 JSON，请检查后重试');
                    return;
                }
                if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
                    ElementPlus.ElMessage.error('自定义请求字段必须是 JSON 对象（不能是数组或标量）');
                    return;
                }
                customFields = parsed;
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
                presencePenalty: this.showAiAdvanced ? this.aiForm.presencePenalty : null,
                customFields
            };
            this.postSave('settings/' + this.aiDialogType + '/save', body, 'ai');
        },

        /** 展开高级参数；已清空的项恢复为默认值，避免滑块 / 数字框拿到 null */
        toggleAiAdvanced() {
            if (!this.showAiAdvanced) {
                this.restoreAiAdvanced();
            }
            this.showAiAdvanced = !this.showAiAdvanced;
        },

        /** 把所有为 null 的高级参数恢复为默认值 */
        restoreAiAdvanced() {
            const f = this.aiForm;
            Object.keys(AI_ADVANCED_DEFAULTS).forEach(key => {
                if (f[key] == null) f[key] = AI_ADVANCED_DEFAULTS[key];
            });
        },

        /** 清空全部高级参数：收起折叠区，保存时全部置 null */
        clearAiAdvanced() {
            this.aiForm.temperature = null;
            this.aiForm.top_p = null;
            this.aiForm.maxTokens = null;
            this.aiForm.frequencyPenalty = null;
            this.aiForm.presencePenalty = null;
            this.aiForm.reasoningEffort = null;
            this.showAiAdvanced = false;
            ElementPlus.ElMessage.success('高级参数已清空，保存后将使用 API 默认值');
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
