/**
 * 网络搜索工具设置组件
 *
 * 展示：网络搜索条目（API Key 配置状态）
 * 修改：对话框内配置 MetaSOAI / Serper 两个搜索引擎的 API Key
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const WebSearchSettings = {
    name: 'WebSearchSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="search" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">网络搜索</span>
                    <span class="settings-item-desc">API Key 配置</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ (settings.metasoApiKey || settings.serperApiKey) ? '已配置' : '未配置' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.websearch" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="search" style="width:20px;height:20px"></i>
                    <span>网络搜索</span>
                </div>
            </template>
            <el-form :model="webSearchForm" label-width="90px" label-position="left">
                <el-form-item label="MetaSOAI Key">
                    <input class="settings-input" v-model="webSearchForm.metasoApiKey" placeholder="国内/中文搜索（默认引擎）">
                </el-form-item>
                <el-form-item label="Serper Key">
                    <input class="settings-input" v-model="webSearchForm.serperApiKey" placeholder="Google 搜索（国外/英文场景）">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.websearch = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveWebSearch" :disabled="saving.websearch">
                        <span v-if="saving.websearch" class="btn-spinner"></span>
                        <span>{{ saving.websearch ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { websearch: false },
            webSearchForm: { metasoApiKey: '', serperApiKey: '' }
        };
    },

    methods: {
        openDialog() {
            this.webSearchForm = {
                metasoApiKey: this.settings.metasoApiKey || '',
                serperApiKey: this.settings.serperApiKey || ''
            };
            this.dialogs.websearch = true;
            this.$nextTick(() => lucide.createIcons());
        },

        saveWebSearch() {
            const metasoKey = this.webSearchForm.metasoApiKey.trim();
            const serperKey = this.webSearchForm.serperApiKey.trim();
            if (!metasoKey && !serperKey) {
                ElementPlus.ElMessage.warning('至少需要配置一个搜索引擎的 API Key');
                return;
            }
            this.postSave('settings/websearch/save', {
                metasoApiKey: metasoKey,
                serperApiKey: serperKey
            }, 'websearch');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
