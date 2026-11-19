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
 * 展示：对话模型 / 高级对话模型 / 小熊崽模型 / Operator / 任务模型 / TaskAI 六个条目
 * 修改：共享对话框编辑各类型的适配器、模型、流式/思考、高级参数（可折叠）
 * 助手设定（System Prompt）已移至「助手设置」，此处不再配置
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
        <div class="settings-item" @click="openDialog('operator')">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="monitor" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">Operator 模型</span>
                    <span class="settings-item-desc">{{ getAiSummary('operator') }} · Computer Use（视觉 + 桌面操控）</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ getAiShort('operator') }}</span>
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
        <div class="settings-item" @click="openCloneModels">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="boxes" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">执行体模型列表</span>
                    <span class="settings-item-desc">{{ cloneModelsSummary }} · 执行型子 Agent 派活时按 key 选模型</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ cloneModels.length }} 个</span>
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
                    <el-select v-model="aiForm.adapterName" placeholder="请选择适配器" style="width:100%" @change="onAdapterChange">
                        <el-option v-for="name in adapterList" :key="name" :label="name" :value="name"></el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="模型">
                    <el-select v-model="aiForm.model" filterable allow-create default-first-option clearable
                               :loading="modelLoading" placeholder="选择或输入模型名称" style="width:100%">
                        <el-option v-for="m in modelOptions" :key="m.id" :label="m.id" :value="m.id">
                            <el-tooltip v-if="hasModelDetail(m)" effect="light" placement="right" :show-after="200"
                                        popper-class="model-detail-tooltip">
                                <template #content>
                                    <model-detail-tip :details="m.details"></model-detail-tip>
                                </template>
                                <span style="display:block;width:100%">{{ m.id }}</span>
                            </el-tooltip>
                            <span v-else>{{ m.id }}</span>
                        </el-option>
                    </el-select>
                    <div v-if="modelListHint" style="font-size:12px;color:#909399;line-height:1.6;margin-top:4px">{{ modelListHint }}</div>
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

        <!-- 执行体模型列表：内置场景条目（只读）+ 用户追加条目（整表 CRUD），改动在本地完成，点「保存列表」一次性提交 -->
        <el-dialog v-model="dialogs.cloneModels" title="" width="760px" class="settings-dialog" :close-on-click-modal="false">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="boxes" style="width:20px;height:20px"></i>
                    <span>执行体模型列表</span>
                </div>
            </template>
            <div class="clone-models-tip">
                clone_assistant_tool 派活时以 model 参数传 key 选模型。内置条目来自上方各场景模型，实时同步、只读展示；
                新增同名 key 可覆盖内置（删除覆盖条目后内置自动恢复）；追加条目增删改后点「保存列表」生效，下一轮对话即可见。
            </div>
            <div v-if="!cloneModels.length" class="clone-models-empty">
                还没有可用的执行体模型：先在上方为各场景配置模型（生成内置条目），或点右下角「新增模型」追加一条。
            </div>
            <div v-for="(m, i) in cloneModels" :key="i" class="clone-model-row" :class="{ 'clone-model-row-builtin': m.builtIn }">
                <div class="clone-model-main">
                    <div class="clone-model-keyline">
                        <span class="clone-model-key">{{ m.key }}</span>
                        <span v-if="m.builtIn" class="clone-model-tag clone-model-tag-builtin">内置</span>
                        <span class="clone-model-model">{{ m.model }} @ {{ m.adapterName }}</span>
                    </div>
                    <div class="clone-model-desc">{{ m.desc || '（无描述）' }}</div>
                </div>
                <div class="clone-model-tags">
                    <span v-if="m.thinking" class="clone-model-tag">思考</span>
                    <span v-if="m.reasoningEffort" class="clone-model-tag">{{ m.reasoningEffort }}</span>
                </div>
                <div v-if="!m.builtIn" class="clone-model-actions">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="openCloneEdit(i)">编辑</button>
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="removeCloneModel(i)">删除</button>
                </div>
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="openCloneEdit(-1)">新增模型</button>
                    <div style="flex:1"></div>
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.cloneModels = false">关闭</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveCloneModels" :disabled="saving.cloneModels">
                        <span v-if="saving.cloneModels" class="btn-spinner"></span>
                        <span>{{ saving.cloneModels ? '保存中...' : '保存列表' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>

        <!-- 执行体模型：单条编辑（新增 / 修改共用） -->
        <el-dialog v-model="dialogs.cloneEdit" title="" width="640px" class="settings-dialog" :close-on-click-modal="false" append-to-body>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="cpu" style="width:20px;height:20px"></i>
                    <span>{{ cloneEditIndex < 0 ? '新增执行体模型' : '编辑执行体模型 · ' + cloneForm.key }}</span>
                </div>
            </template>
            <el-form :model="cloneForm" label-width="110px" label-position="left">
                <el-form-item label="key">
                    <el-input v-model="cloneForm.key" placeholder="工具调用时传入的模型标识，如 deepseek-code-fast"></el-input>
                </el-form-item>
                <el-form-item label="用途描述">
                    <el-input v-model="cloneForm.desc" placeholder="一句话说明适合什么任务，会展示给 AI 供其选择"></el-input>
                </el-form-item>
                <el-form-item label="适配器">
                    <el-select v-model="cloneForm.adapterName" placeholder="请选择适配器" style="width:100%" @change="onCloneAdapterChange">
                        <el-option v-for="name in adapterList" :key="name" :label="name" :value="name"></el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="模型">
                    <el-select v-model="cloneForm.model" filterable allow-create default-first-option clearable
                               :loading="cloneModelLoading" placeholder="选择或输入模型名称" style="width:100%">
                        <el-option v-for="m in cloneModelOptions" :key="m.id" :label="m.id" :value="m.id"></el-option>
                    </el-select>
                    <div v-if="cloneModelHint" style="font-size:12px;color:#909399;line-height:1.6;margin-top:4px">{{ cloneModelHint }}</div>
                </el-form-item>
                <el-form-item label="思考模式">
                    <el-switch v-model="cloneForm.thinking" active-text="开启" inactive-text="关闭"></el-switch>
                </el-form-item>
                <el-form-item label="推理深度">
                    <el-select v-model="cloneForm.reasoningEffort" style="width:100%">
                        <el-option label="未设置（API 默认）" value=""></el-option>
                        <el-option label="low" value="low"></el-option>
                        <el-option label="high" value="high"></el-option>
                        <el-option label="max" value="max"></el-option>
                    </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.cloneEdit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="confirmCloneEdit">确定</button>
                </div>
            </template>
        </el-dialog>

        <!-- 通用确认弹窗：删除 / 覆盖内置等二次确认走这里（项目统一风格，替代 ElMessageBox） -->
        <confirm-dialog ref="confirmDialog"></confirm-dialog>
    </div>`,

    data() {
        return {
            dialogs: { ai: false, cloneModels: false, cloneEdit: false },
            // 当前 AI 对话框的类型 (chat/chat_pro/cub/operator/mission/task)
            aiDialogType: 'chat',
            showAiAdvanced: false,
            aiForm: { adapterName: '', model: '', stream: false, thinking: false, reasoningEffort: null, temperature: 1, top_p: 1, maxTokens: 4096, frequencyPenalty: 0, presencePenalty: 0, customFieldsText: '' },
            // 可用适配器列表
            adapterList: [],
            // 当前适配器返回的可选模型列表（为空时仍可手动输入）
            modelList: [],
            modelLoading: false,
            modelListHint: '',
            // 执行体模型列表（clone_assistant_tool 的 model key 表，独立于上面的场景模型）
            cloneModels: [],
            // 正在编辑的执行体模型下标，-1 = 新增
            cloneEditIndex: -1,
            cloneForm: { key: '', desc: '', adapterName: '', model: '', thinking: false, reasoningEffort: '' },
            // 执行体模型编辑框的模型下拉状态（与 aiForm 的 modelList 互不相干）
            cloneModelList: [],
            cloneModelLoading: false,
            cloneModelHint: ''
        };
    },

    computed: {
        aiDialogTitle() {
            return AI_TYPE_NAMES[this.aiDialogType] || 'AI 模型';
        },

        /** 下拉选项：远端模型列表 + 当前已配置的模型（避免历史模型不在列表里时显示为空） */
        modelOptions() {
            const list = (this.modelList || []).slice();
            const current = (this.aiForm.model || '').trim();
            if (current && !list.some(m => m.id === current)) list.unshift({ id: current });
            return list;
        },

        /** 高级参数是否已配置（任一非 null）；全 null 即「未配置」，打开对话框时默认收起 */
        hasAiAdvanced() {
            return Object.keys(AI_ADVANCED_DEFAULTS).some(key => this.aiForm[key] != null);
        },

        /** 执行体模型列表摘要（settings-item 副标题用）：总数带内置 / 追加拆分 */
        cloneModelsSummary() {
            if (!this.cloneModels.length) return '未配置';
            const extras = this.cloneModels.filter(m => !m.builtIn).length;
            return this.cloneModels.length + ' 个（内置 ' + (this.cloneModels.length - extras) + ' + 追加 ' + extras + '）：'
                + this.cloneModels.map(m => m.key).join('、');
        },

        /** 执行体模型编辑框的模型下拉选项：远端列表 + 当前已配置的模型兜底 */
        cloneModelOptions() {
            const list = (this.cloneModelList || []).slice();
            const current = (this.cloneForm.model || '').trim();
            if (current && !list.some(m => m.id === current)) list.unshift({ id: current });
            return list;
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
            this.fetchModelList();
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
        },

        /* ---------- 模型列表（切换适配器时重新拉取） ---------- */
        onAdapterChange() {
            // 模型与适配器绑定，切换后清空，等待新列表或手动输入
            this.aiForm.model = '';
            this.fetchModelList();
        },

        async fetchModelList() {
            const apiName = (this.aiForm.adapterName || '').trim();
            this.modelList = [];
            this.modelListHint = '';
            if (!apiName) {
                return;
            }
            this.modelLoading = true;
            try {
                const r = await API.settings.adapters.models(apiName);
                if (r.status === 200) {
                    this.modelList = r.data || [];
                    if (!this.modelList.length) {
                        this.modelListHint = '该适配器未返回模型列表，可直接输入模型名称';
                    }
                } else {
                    this.modelListHint = '获取模型列表失败，可直接输入模型名称';
                }
            } catch (e) {
                console.error(e);
                this.modelListHint = '获取模型列表失败，可直接输入模型名称';
            } finally {
                this.modelLoading = false;
            }
        },

        /** 是否存在厂商返回的模型详情（决定是否显示悬浮提示） */
        hasModelDetail(m) {
            return !!(m && ModelDetailUtils.has(m.details));
        },

        /* ---------- 执行体模型列表（clone_assistant_tool） ---------- */

        openCloneModels() {
            this.fetchCloneModels();
            this.dialogs.cloneModels = true;
            this.$nextTick(() => lucide.createIcons());
        },

        async fetchCloneModels() {
            try {
                const r = await API.get('settings/clone-models/get');
                if (r.status === 200) {
                    this.cloneModels = (r.data || []).map(m => ({
                        key: m.key || '',
                        desc: m.desc || '',
                        adapterName: m.adapterName || '',
                        model: m.model || '',
                        thinking: !!m.thinking,
                        reasoningEffort: m.reasoningEffort || null,
                        builtIn: !!m.builtIn
                    }));
                }
            } catch (e) {
                console.error(e);
            }
        },

        /** 打开单条编辑框：index=-1 为新增，否则填入当前条 */
        openCloneEdit(index) {
            this.cloneEditIndex = index;
            const m = index >= 0 ? this.cloneModels[index] : null;
            this.cloneForm = {
                key: m ? m.key : '',
                desc: m ? m.desc : '',
                adapterName: m ? m.adapterName : '',
                model: m ? m.model : '',
                thinking: m ? !!m.thinking : false,
                reasoningEffort: m && m.reasoningEffort ? m.reasoningEffort : ''
            };
            // 模型下拉独立于主 AI 编辑框：进编辑框时按当前适配器拉一次
            this.cloneModelList = [];
            this.cloneModelHint = '';
            if (this.cloneForm.adapterName) this.fetchCloneModelList();
            this.dialogs.cloneEdit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        onCloneAdapterChange() {
            // 模型与适配器绑定，切换后清空，等待新列表或手动输入
            this.cloneForm.model = '';
            this.fetchCloneModelList();
        },

        async fetchCloneModelList() {
            const apiName = (this.cloneForm.adapterName || '').trim();
            this.cloneModelList = [];
            this.cloneModelHint = '';
            if (!apiName) return;
            this.cloneModelLoading = true;
            try {
                const r = await API.settings.adapters.models(apiName);
                if (r.status === 200) {
                    this.cloneModelList = r.data || [];
                    if (!this.cloneModelList.length) {
                        this.cloneModelHint = '该适配器未返回模型列表，可直接输入模型名称';
                    }
                } else {
                    this.cloneModelHint = '获取模型列表失败，可直接输入模型名称';
                }
            } catch (e) {
                console.error(e);
                this.cloneModelHint = '获取模型列表失败，可直接输入模型名称';
            } finally {
                this.cloneModelLoading = false;
            }
        },

        /** 校验通过后把编辑框内容写回本地列表（新增 push / 编辑 splice），不触发网络请求。
         *  key 撞追加条目直接拦截；撞内置条目弹覆盖确认——确认后替换内置位置上的那条
         *  （与后端合并语义一致：覆盖条目可编辑删除，删除保存后内置自动回来）。 */
        async confirmCloneEdit() {
            const key = (this.cloneForm.key || '').trim();
            const adapter = (this.cloneForm.adapterName || '').trim();
            const model = (this.cloneForm.model || '').trim();
            if (!key) { ElementPlus.ElMessage.warning('key 不能为空'); return; }
            if (!adapter) { ElementPlus.ElMessage.warning('适配器不能为空'); return; }
            if (!model) { ElementPlus.ElMessage.warning('模型名称不能为空'); return; }
            const clashIndex = this.cloneModels.findIndex((m, i) => i !== this.cloneEditIndex && m.key === key);
            if (clashIndex >= 0) {
                const clash = this.cloneModels[clashIndex];
                if (!clash.builtIn) {
                    ElementPlus.ElMessage.warning('key 已存在：' + key);
                    return;
                }
                try {
                    await this.$refs.confirmDialog.show({
                        title: '覆盖内置条目',
                        message: 'key「' + key + '」与内置场景条目同名，保存后将以这条覆盖内置配置；删除该条并保存后，内置配置会自动恢复。',
                        confirmText: '覆盖',
                        type: 'warning'
                    });
                } catch (e) {
                    return;
                }
            }
            const entry = {
                key: key,
                desc: (this.cloneForm.desc || '').trim(),
                adapterName: adapter,
                model: model,
                thinking: !!this.cloneForm.thinking,
                reasoningEffort: this.cloneForm.reasoningEffort || null,
                builtIn: false
            };
            if (clashIndex >= 0) {
                // 覆盖内置：若正在编辑的是旧条目（改 key 而来），先从原位置移除并校正目标索引
                if (this.cloneEditIndex >= 0 && this.cloneEditIndex !== clashIndex) {
                    this.cloneModels.splice(this.cloneEditIndex, 1);
                    if (this.cloneEditIndex < clashIndex) clashIndex--;
                }
                // 替换内置位置上的条目，本地视图与后端合并结果保持一致
                this.cloneModels.splice(clashIndex, 1, entry);
            } else if (this.cloneEditIndex < 0) {
                this.cloneModels.push(entry);
            } else {
                this.cloneModels.splice(this.cloneEditIndex, 1, entry);
            }
            this.dialogs.cloneEdit = false;
        },

        async removeCloneModel(index) {
            const m = this.cloneModels[index];
            if (!m || m.builtIn) return; // 内置条目不渲染删除按钮，此处双保险
            try {
                await this.$refs.confirmDialog.show({
                    title: '删除执行体模型',
                    message: '确定删除「' + m.key + '」？删除后需保存列表才生效。',
                    confirmText: '删除',
                    type: 'danger'
                });
            } catch (e) {
                return;
            }
            this.cloneModels.splice(index, 1);
            ElementPlus.ElMessage.success('已删除「' + m.key + '」，记得保存列表');
        },

        /** 整表提交追加条目（内置条目由场景设置实时注入，不提交不落盘）；
         *  成功后 postSave 自动关列表框，这里再重拉一次拿后端合并归一化后的数据 */
        async saveCloneModels() {
            const extras = this.cloneModels.filter(m => !m.builtIn);
            const ok = await this.postSave('settings/clone-models/save', extras, 'cloneModels');
            if (ok) this.fetchCloneModels();
        }
    },

    mounted() {
        this.fetchAdapterList();
        this.fetchCloneModels();
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
