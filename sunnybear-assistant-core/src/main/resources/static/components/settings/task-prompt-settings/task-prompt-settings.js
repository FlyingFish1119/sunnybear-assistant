/**
 * 任务提示词管理组件
 *
 * 展示：管理提示词（提示词数量摘要，列表自行加载）
 * 修改：管理对话框内查看全部提示词，支持添加、编辑、删除（删除经确认弹窗）
 * 添加 / 编辑 / 删除后自行刷新列表
 */
const TaskPromptSettings = {
    name: 'TaskPromptSettings',

    mixins: [SettingsCommon],

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    template: `
    <div>
        <div class="settings-item" @click="openManage">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="list" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">管理提示词</span>
                    <span class="settings-item-desc">共 {{ taskPromptList.length }} 条提示词 · 预定义 step 系统提示词模板</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ taskPromptList.length }} 条</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.taskpromptmanage" title="" width="900px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close @open="fetchTaskPromptList">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="file-text" style="width:20px;height:20px"></i>
                    <span>Step 提示词管理</span>
                </div>
            </template>
            <div style="margin-bottom:16px;display:flex;justify-content:flex-end">
                <button type="button" class="dialog-btn dialog-btn-save" @click="openTaskPromptEdit(null)" style="padding:6px 16px;font-size:13px">
                    <i data-lucide="plus" style="width:14px;height:14px"></i> 添加提示词
                </button>
            </div>
            <div v-if="taskPromptLoading" style="text-align:center;padding:40px;color:#909399">加载中...</div>
            <div v-else-if="taskPromptList.length === 0" style="text-align:center;padding:40px;color:#909399">暂无提示词，点击上方按钮添加</div>
            <div v-else class="entry-list">
                <div v-for="item in taskPromptList" :key="item.type" class="entry-item">
                    <div class="entry-item-body">
                        <div class="entry-item-title">
                            <code style="background:#f0f0f0;padding:1px 6px;border-radius:3px;font-size:12px">{{ item.type }}</code>
                            <span style="margin-left:8px;color:#909399;font-size:12px">{{ item.description }}</span>
                        </div>
                        <div class="entry-item-content" style="white-space:pre-wrap;max-height:60px;overflow:hidden">{{ item.prompt }}</div>
                    </div>
                    <div class="entry-item-actions">
                        <button type="button" class="entry-action-btn edit" @click="openTaskPromptEdit(item)" title="编辑">
                            <i data-lucide="pencil" style="width:15px;height:15px"></i>
                        </button>
                        <button type="button" class="entry-action-btn delete" @click="confirmDeleteTaskPrompt(item)" title="删除">
                            <i data-lucide="trash-2" style="width:15px;height:15px"></i>
                        </button>
                    </div>
                </div>
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.taskpromptmanage = false">关闭</button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogs.taskpromptedit" title="" width="800px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="file-text" style="width:20px;height:20px"></i>
                    <span>{{ taskPromptEditForm._isNew ? '添加提示词' : '编辑提示词' }}</span>
                </div>
            </template>
            <el-form :model="taskPromptEditForm" label-width="80px" label-position="left">
                <el-form-item label="类型 (type)">
                    <input class="settings-input" v-model="taskPromptEditForm.type" placeholder="例如: my_custom_type" maxlength="50"
                        :disabled="!taskPromptEditForm._isNew" :style="!taskPromptEditForm._isNew ? 'background:#f5f5f5' : ''">
                </el-form-item>
                <el-form-item label="描述">
                    <input class="settings-input" v-model="taskPromptEditForm.description" placeholder="一句话说明该类型用途" maxlength="200">
                </el-form-item>
                <el-form-item label="提示词">
                    <textarea class="settings-textarea" v-model="taskPromptEditForm.prompt" rows="12"
                        placeholder="纯角色/行为指令。步骤信息会通过 user prompt 传入，这里不需要占位符。"></textarea>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.taskpromptedit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveTaskPrompt" :disabled="saving.taskprompt">
                        <span v-if="saving.taskprompt" class="btn-spinner"></span>
                        <span>{{ saving.taskprompt ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>

        <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    </div>`,

    data() {
        return {
            dialogs: { taskpromptmanage: false, taskpromptedit: false },
            taskPromptList: [],
            taskPromptLoading: false,
            taskPromptEditForm: { _isNew: true, type: '', prompt: '', description: '' }
        };
    },

    methods: {
        openManage() {
            this.dialogs.taskpromptmanage = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 任务提示词管理 ---------- */
        async fetchTaskPromptList() {
            this.taskPromptLoading = true;
            try {
                const r = await API.taskPrompt.list();
                if (r.status === 200) {
                    this.taskPromptList = r.data || [];
                }
            } catch (e) {
                console.error(e);
            } finally {
                this.taskPromptLoading = false;
            }
        },

        openTaskPromptEdit(item) {
            if (item) {
                this.taskPromptEditForm = { _isNew: false, type: item.type, prompt: item.prompt, description: item.description || '' };
            } else {
                this.taskPromptEditForm = { _isNew: true, type: '', prompt: '', description: '' };
            }
            this.dialogs.taskpromptedit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        async saveTaskPrompt() {
            if (!this.taskPromptEditForm.type.trim()) {
                ElementPlus.ElMessage.warning('type 不能为空');
                return;
            }
            if (!this.taskPromptEditForm.prompt.trim()) {
                ElementPlus.ElMessage.warning('提示词内容不能为空');
                return;
            }
            this.saving.taskprompt = true;
            try {
                const r = await API.taskPrompt.save({
                    type: this.taskPromptEditForm.type.trim(),
                    prompt: this.taskPromptEditForm.prompt,
                    description: this.taskPromptEditForm.description || ''
                });
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.dialogs.taskpromptedit = false;
                    await this.fetchTaskPromptList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving.taskprompt = false;
            }
        },

        async confirmDeleteTaskPrompt(item) {
            try {
                await this.$refs.confirmDialog.show({
                    title: '确认删除',
                    message: '确定要删除提示词「' + item.type + '」吗？此操作不可恢复。',
                    confirmText: '确认删除',
                    cancelText: '取消',
                    type: 'warning'
                });
                const r = await API.taskPrompt.delete(item.type);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('删除成功');
                    await this.fetchTaskPromptList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '删除失败');
                }
            } catch (e) {
                if (e !== undefined && e !== 'cancel' && e !== 'close') {
                    ElementPlus.ElMessage.error('网络请求失败');
                    console.error(e);
                }
            }
        }
    },

    mounted() {
        // 静默加载列表（用于显示条目数量）
        this.fetchTaskPromptList();
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
