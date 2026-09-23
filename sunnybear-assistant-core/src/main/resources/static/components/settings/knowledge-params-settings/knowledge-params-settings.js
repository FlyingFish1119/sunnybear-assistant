/**
 * 知识库参数设置组件（与记忆注入开关同形态）
 *
 * 展示：知识库注入开关状态（开启后每次对话自动挑选相关知识条目注入）
 *       置信度阈值（仅注入概率高于该值的知识条目）
 * 修改：开关直接切换，滑轨拖动结束后保存；保存成功后 emit('saved')，由父组件刷新全部设置
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
        <div class="settings-item">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="database" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">知识库注入</span>
                    <span class="settings-item-desc">{{ enable ? '已开启 · 对话会自动挑选知识条目注入' : '已关闭 · 对话不再注入知识条目' }}</span>
                </div>
            </div>
            <div class="settings-item-right">
                <el-switch v-model="enable" @change="saveKnowledgeEnable" :disabled="saving.knowledgeenable"></el-switch>
            </div>
        </div>
        <div class="settings-item knowledge-threshold">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="sliders-horizontal" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">置信度阈值</span>
                    <span class="settings-item-desc">仅注入置信度高于该值的知识条目，越低召回越多</span>
                </div>
            </div>
            <div class="settings-item-right">
                <el-slider v-model="confidenceThreshold" :min="0" :max="1" :step="0.05"
                    show-input :format-tooltip="v => v.toFixed(2)" :disabled="saving.knowledgethreshold"
                    @change="saveKnowledgeThreshold"></el-slider>
            </div>
        </div>
    </div>`,

    data() {
        return {
            enable: false,
            confidenceThreshold: 0.5
        };
    },

    watch: {
        // 父组件刷新设置后同步开关与阈值状态
        settings: {
            handler(v) {
                this.enable = !!(v && v.enable);
                this.confidenceThreshold = (v && v.confidenceThreshold != null) ? v.confidenceThreshold : 0.5;
            },
            immediate: true
        }
    },

    methods: {
        async saveKnowledgeEnable(val) {
            this.saving.knowledgeenable = true;
            try {
                const r = await API.settings.knowledgesettings.save({
                    enable: !!val,
                    confidenceThreshold: this.confidenceThreshold
                });
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.$emit('saved');
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                    this.enable = !val;
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                this.enable = !val;
                console.error(e);
            } finally {
                this.saving.knowledgeenable = false;
            }
        },

        async saveKnowledgeThreshold(val) {
            this.saving.knowledgethreshold = true;
            try {
                const r = await API.settings.knowledgesettings.save({
                    enable: this.enable,
                    confidenceThreshold: val
                });
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.$emit('saved');
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving.knowledgethreshold = false;
            }
        }
    }
};
