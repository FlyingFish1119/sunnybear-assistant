/**
 * 模型切换组件（发送区插件）
 *
 * 定位：设置页的简化版，只覆盖对话（chat）/ 高级（chat_pro）两种模型配置，
 *       只提供适配器、模型名称、思考强度（reasoningEffort）三个可调项。
 *
 * 通过 SendAreaPlugins 注册到发送区锚点，按页面可裁剪：
 *   SendAreaPlugins.registerSlot('toolbar-right', ModelSwitcher, 0);
 * 未注册的页面（如角色页，角色自带模型）不会渲染本组件。
 *
 * 触发按钮显示当前会话实际生效的模型名（依据 sessionStore.currentSession.enablePro
 * 选 chat / chat_pro），打开面板后自动定位到对应 tab。
 *
 * 保存时先取服务端完整 AISettings 再合并编辑项提交，避免把 stream / thinking /
 * customFields 等未在本面板暴露的字段冲成默认值。
 *
 * Props:
 *   mainColor — String 主题色
 *
 * Injects:
 *   sessionStore — 会话仓库（可选）；读取 currentSession.enablePro
 */
const ModelSwitcher = {
    name: 'ModelSwitcher',

    template: `
    <div class="model-switcher" :style="{'--main-color': mainColor}">
        <button type="button" class="model-switcher-toggle" :class="{ 'is-open': panelOpen }"
                @click.stop="togglePanel" title="模型设置（对话 / 高级）">
            <i data-lucide="cpu"></i>
            <span class="model-switcher-label">{{ triggerLabel }}</span>
            <i data-lucide="chevron-up" class="model-switcher-caret"></i>
        </button>
        <transition name="model-switcher-fade">
            <div v-if="panelOpen" class="model-switcher-backdrop" @click="panelOpen = false"></div>
        </transition>
        <transition name="model-switcher-pop">
        <div v-if="panelOpen" class="model-switcher-panel" @click.stop>
            <div class="model-switcher-tabs">
                <button type="button" :class="{ active: tab === 'chat' }"
                        @click="switchTab('chat')">对话模型</button>
                <button type="button" :class="{ active: tab === 'chat_pro' }"
                        @click="switchTab('chat_pro')">高级模型</button>
            </div>
            <div class="model-switcher-field">
                <label>适配器</label>
                <el-select v-model="form.adapterName" size="small" placeholder="请选择适配器"
                           style="width:100%" @change="onAdapterChange">
                    <el-option v-for="name in adapters" :key="name" :label="name" :value="name"></el-option>
                </el-select>
            </div>
            <div class="model-switcher-field">
                <label>模型名称</label>
                <el-select v-model="form.model" size="small" filterable allow-create
                           default-first-option clearable :loading="modelLoading"
                           placeholder="选择或输入模型名称" style="width:100%">
                    <el-option v-for="m in modelOptions" :key="m.id" :label="m.id" :value="m.id"></el-option>
                </el-select>
            </div>
            <div class="model-switcher-field model-switcher-field--slider">
                <label>
                    <span>思考强度</span>
                    <button v-if="form.reasoningEffort != null" type="button"
                            class="model-switcher-clear" @click="form.reasoningEffort = null">清空</button>
                </label>
                <el-slider v-if="form.reasoningEffort != null" v-model="form.reasoningEffort"
                           :min="0" :max="2" :step="1" show-stops :marks="{0:'低',1:'高',2:'最深'}"
                           :format-tooltip="v => ['低 (low)','高 (high)','最深 (max)'][v]"></el-slider>
                <div v-else class="model-switcher-unset">
                    <span>未设置 · 保存后使用 API 默认值</span>
                    <button type="button" @click="form.reasoningEffort = 1">恢复</button>
                </div>
            </div>
            <div class="model-switcher-actions">
                <button type="button" class="model-switcher-save" :disabled="saving"
                        @click="save">{{ saving ? '保存中...' : '保存' }}</button>
            </div>
        </div>
        </transition>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        // 可选：读取当前会话的 enablePro 决定触发文案与默认 tab
        sessionStore: { default: null },
        // 可选：保存成功后广播本地事件，供顶栏等同步模型名
        wsBus: { default: null }
    },

    data() {
        return {
            panelOpen: false,
            tab: 'chat',                 // 'chat' | 'chat_pro'
            configs: { chat: null, chat_pro: null },  // 服务端完整 AISettings
            adapters: [],
            modelList: [],
            modelLoading: false,
            saving: false,
            form: { adapterName: '', model: '', reasoningEffort: null }  // reasoningEffort: null | 0 | 1 | 2
        };
    },

    computed: {
        /** 触发按钮文案：优先当前会话实际生效的模型（无会话时回退对话模型） */
        triggerLabel() {
            const session = this.sessionStore ? this.sessionStore.currentSession : null;
            const pro = !!(session && session.enablePro);
            const cfg = this.configs[pro ? 'chat_pro' : 'chat'] || this.configs.chat || this.configs.chat_pro;
            return (cfg && cfg.model) || '模型';
        },
        /** 模型下拉选项：远端列表 + 当前值（避免历史模型不在列表里时显示为空） */
        modelOptions() {
            const list = (this.modelList || []).slice();
            const current = (this.form.model || '').trim();
            if (current && !list.some(m => m.id === current)) list.unshift({ id: current });
            return list;
        }
    },

    methods: {
        /** 打开/收起面板；打开时按当前会话模式定位 tab 并回填表单 */
        togglePanel() {
            this.panelOpen = !this.panelOpen;
            if (!this.panelOpen) return;
            const session = this.sessionStore ? this.sessionStore.currentSession : null;
            this.tab = (session && session.enablePro) ? 'chat_pro' : 'chat';
            this.fillForm();
            this.fetchAdapters();
            this.fetchModelList();
            this.$nextTick(() => {
                if (typeof lucide !== 'undefined') lucide.createIcons();
            });
        },

        /** 切换编辑对象（chat / chat_pro） */
        switchTab(tab) {
            if (this.tab === tab) return;
            this.tab = tab;
            this.fillForm();
            this.fetchModelList();
        },

        /** 用服务端配置回填表单（reasoningEffort 字符串 → 滑块下标） */
        fillForm() {
            const cfg = this.configs[this.tab] || {};
            this.form = {
                adapterName: cfg.adapterName || '',
                model: cfg.model || '',
                reasoningEffort: cfg.reasoningEffort != null
                    ? ['low', 'high', 'max'].indexOf(cfg.reasoningEffort)
                    : null
            };
        },

        /** 拉取 chat / chat_pro 完整配置（触发文案 + 保存时补齐其它字段） */
        async fetchConfigs() {
            try {
                const [chatRes, proRes] = await Promise.all([
                    API.settings.chat.get(),
                    API.settings.chat_pro.get()
                ]);
                if (chatRes.status === 200) this.configs.chat = chatRes.data || {};
                if (proRes.status === 200) this.configs.chat_pro = proRes.data || {};
            } catch (e) {
                console.error('获取模型设置失败:', e);
            }
        },

        /** 适配器列表（首次打开面板时拉取一次） */
        async fetchAdapters() {
            if (this.adapters.length) return;
            try {
                const r = await API.settings.adapters.list();
                if (r.status === 200) this.adapters = r.data || [];
            } catch (e) {
                console.error(e);
            }
        },

        /** 当前适配器的可选模型列表（为空时仍可手动输入） */
        async fetchModelList() {
            const apiName = (this.form.adapterName || '').trim();
            this.modelList = [];
            if (!apiName) return;
            this.modelLoading = true;
            try {
                const r = await API.settings.adapters.models(apiName);
                if (r.status === 200) this.modelList = r.data || [];
            } catch (e) {
                console.error(e);
            } finally {
                this.modelLoading = false;
            }
        },

        onAdapterChange() {
            // 模型与适配器绑定，切换后清空，等待新列表或手动输入
            this.form.model = '';
            this.fetchModelList();
        },

        /** 保存：把编辑项合并回完整配置再提交（避免丢失 stream / thinking 等字段） */
        async save() {
            const adapterName = (this.form.adapterName || '').trim();
            const model = (this.form.model || '').trim();
            if (!adapterName) {
                ElementPlus.ElMessage.warning('适配器名称不能为空');
                return;
            }
            if (!model) {
                ElementPlus.ElMessage.warning('模型名称不能为空');
                return;
            }
            const cfg = this.configs[this.tab] || {};
            const body = Object.assign({}, cfg, {
                adapterName: adapterName,
                model: model,
                reasoningEffort: this.form.reasoningEffort == null
                    ? null
                    : ['low', 'high', 'max'][this.form.reasoningEffort]
            });
            this.saving = true;
            try {
                const r = this.tab === 'chat_pro'
                    ? await API.settings.chat_pro.save(body)
                    : await API.settings.chat.save(body);
                if (r.status === 200) {
                    this.configs[this.tab] = body;
                    // 广播本地事件，让顶栏等展示处同步新模型名
                    if (this.wsBus) this.wsBus.emit('settings:model-updated', { type: this.tab, model: model });
                    ElementPlus.ElMessage.success('模型设置已保存');
                    // 保存成功后自动收起面板
                    this.panelOpen = false;
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving = false;
            }
        }
    },

    mounted() {
        // 预取配置，供触发按钮显示当前模型名
        this.fetchConfigs();
    },

    updated() {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(() => lucide.createIcons());
        }
    }
};
