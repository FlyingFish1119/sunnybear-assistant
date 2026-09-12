/**
 * 工具集（Kit）可见性设置
 *
 * 按工具集粒度控制「哪些工具注入主对话」。开关关掉 = 该工具集的工具不再注入主对话；
 * 只影响主对话这条全量注入路径，子 Agent 与角色对话走各自的允许表，不受影响。
 *
 * 后端数据：GET settings/tools/kits
 *   [{ id, name, description, defaultVisible, visible, tools: [{ name, description }] }]
 * - id 是 kit 全限定类名，也是保存时的键
 * - defaultVisible 是代码声明（false 表示默认不对主对话开放，如角色骰子/战斗类工具集）
 * - visible 是「声明 + 用户配置」算出来的最终结果，开关初始状态取它
 *
 * 保存：POST settings/toolkit/save { visibility: { kitId: bool } }，整表覆盖。
 * 未知 kitId 由后端忽略并记日志（插件移除后不会存不上）。
 */
const ToolKitSettings = {
    name: 'ToolKitSettings',

    mixins: [SettingsCommon],

    props: {
        kits: { type: Array, default: () => [] }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" style="cursor: default">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="layers" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">主对话注入</span>
                    <span class="settings-item-desc">关掉的工具集不会注入主对话；子 Agent 与角色对话不受影响</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ enabledCount }} / {{ kits.length }} 已启用</span>
            </div>
        </div>

        <div v-for="kit in kits" :key="kit.id" class="settings-item"
             style="flex-direction: column; align-items: stretch"
             @click="toggle(kit.id)">
            <div style="display: flex; align-items: center; justify-content: space-between">
                <div class="settings-item-left">
                    <div class="settings-item-icon"><i data-lucide="package" style="width:16px;height:16px"></i></div>
                    <div class="settings-item-info">
                        <span class="settings-item-label">
                            {{ kit.name }}
                            <span v-if="!kit.defaultVisible"
                                  style="margin-left: 6px; font-size: 11px; font-weight: 700; color: #e6a23c">默认不开放</span>
                        </span>
                        <span class="settings-item-desc">
                            {{ kit.description || '—' }} · {{ kit.tools.length }} 个工具
                        </span>
                    </div>
                </div>
                <div class="settings-item-right">
                    <el-switch v-model="draft[kit.id]"
                               :disabled="saving.toolkit"
                               @click.stop
                               @change="saveVisibility($event, kit)"></el-switch>
                    <!-- 旋转挂在 span 上而不是 <i> 上：lucide 会把 <i> 整个换成 <svg>，
                         往被换掉的节点写 :style 会写丢 -->
                    <span style="display:inline-flex;cursor:pointer;transition:transform 0.25s"
                          :style="{ transform: expanded[kit.id] ? 'rotate(180deg)' : '' }"
                          @click.stop="toggle(kit.id)">
                        <i data-lucide="chevron-down" style="width:14px;height:14px;color:#c0c4cc"></i>
                    </span>
                </div>
            </div>

            <div class="advanced-params" :class="{ expanded: expanded[kit.id] }">
                <div class="advanced-params-inner">
                    <div v-for="tool in kit.tools" :key="tool.name"
                         style="display: flex; gap: 8px; align-items: baseline; font-size: 12px">
                        <code style="flex-shrink: 0; background: #f0f0f0; padding: 1px 6px; border-radius: 3px">{{ tool.name }}</code>
                        <span style="color: var(--text-muted); line-height: 1.5">{{ tool.description }}</span>
                    </div>
                </div>
            </div>
        </div>
    </div>`,

    data() {
        return {
            // kitId → 开关状态。kits 变化时整体重建，保存用整表覆盖
            draft: {},
            // kitId → 工具清单是否展开
            expanded: {}
        };
    },

    computed: {
        enabledCount() {
            return this.kits.filter(kit => this.draft[kit.id]).length;
        }
    },

    watch: {
        // 父组件刷新设置后同步开关状态
        kits: {
            handler(list) {
                const next = {};
                (list || []).forEach(kit => {
                    next[kit.id] = !!kit.visible;
                });
                this.draft = next;
            },
            immediate: true
        }
    },

    methods: {
        toggle(kitId) {
            this.expanded[kitId] = !this.expanded[kitId];
        },

        async saveVisibility(val, kit) {
            this.saving.toolkit = true;
            try {
                const r = await API.settings.toolkits.save({ visibility: { ...this.draft } });
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.$emit('saved');
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                    this.draft[kit.id] = !val;   // 失败回滚 UI
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                this.draft[kit.id] = !val;
                console.error(e);
            } finally {
                this.saving.toolkit = false;
            }
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
