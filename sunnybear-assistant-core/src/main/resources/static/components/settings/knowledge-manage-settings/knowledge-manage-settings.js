/**
 * 知识条目管理组件
 *
 * 展示：管理知识条目（条目数量摘要，列表自行加载）
 * 修改：管理对话框内查看全部条目，支持添加、编辑、删除（删除经确认弹窗）
 * 添加 / 编辑 / 删除后自行刷新列表
 */
const KnowledgeManageSettings = {
    name: 'KnowledgeManageSettings',

    mixins: [SettingsCommon],

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    template: `
    <div>
        <div class="settings-item" @click="openManage">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="book-open" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">管理知识条目</span>
                    <span class="settings-item-desc">共 {{ knowledgeList.length }} 条知识 · 添加、编辑、删除</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ knowledgeList.length }} 条</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.knowledgemanage" title="" width="800px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close @open="fetchKnowledgeList">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="book-open" style="width:20px;height:20px"></i>
                    <span>知识条目管理</span>
                </div>
            </template>
            <div style="margin-bottom:16px;display:flex;justify-content:flex-end">
                <button type="button" class="dialog-btn dialog-btn-save" @click="openKnowledgeEdit(null)" style="padding:6px 16px;font-size:13px">
                    <i data-lucide="plus" style="width:14px;height:14px"></i> 添加条目
                </button>
            </div>
            <div v-if="knowledgeLoading" style="text-align:center;padding:40px;color:#909399">加载中...</div>
            <div v-else-if="knowledgeList.length === 0" style="text-align:center;padding:40px;color:#909399">暂无知识条目，点击上方按钮添加</div>
            <div v-else class="entry-list">
                <div v-for="item in knowledgeList" :key="item.id" class="entry-item">
                    <div class="entry-item-body">
                        <div class="entry-item-title">{{ item.intro }}</div>
                        <div class="entry-item-content">{{ item.content }}</div>
                        <div class="entry-item-time">{{ formatTime(item.createTime) }}</div>
                    </div>
                    <div class="entry-item-actions">
                        <button type="button" class="entry-action-btn edit" @click="openKnowledgeEdit(item)" title="编辑">
                            <i data-lucide="pencil" style="width:15px;height:15px"></i>
                        </button>
                        <button type="button" class="entry-action-btn delete" @click="confirmDeleteKnowledge(item)" title="删除">
                            <i data-lucide="trash-2" style="width:15px;height:15px"></i>
                        </button>
                    </div>
                </div>
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.knowledgemanage = false">关闭</button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogs.knowledgeedit" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="book-open" style="width:20px;height:20px"></i>
                    <span>{{ knowledgeEditForm.id ? '编辑知识条目' : '添加知识条目' }}</span>
                </div>
            </template>
            <el-form :model="knowledgeEditForm" label-width="80px" label-position="left">
                <el-form-item label="简介">
                    <textarea class="settings-textarea" v-model="knowledgeEditForm.intro" rows="2" placeholder="输入知识简介（约 50 字，比标题内容更丰富，用于匹配检索）" maxlength="200"></textarea>
                </el-form-item>
                <el-form-item label="内容">
                    <textarea class="settings-textarea" v-model="knowledgeEditForm.content" rows="6" placeholder="输入知识内容"></textarea>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.knowledgeedit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveKnowledgeEntry" :disabled="saving.knowledgeentry">
                        <span v-if="saving.knowledgeentry" class="btn-spinner"></span>
                        <span>{{ saving.knowledgeentry ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>

        <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    </div>`,

    data() {
        return {
            dialogs: { knowledgemanage: false, knowledgeedit: false },
            knowledgeList: [],
            knowledgeLoading: false,
            knowledgeEditForm: { id: null, intro: '', content: '' }
        };
    },

    methods: {
        openManage() {
            this.dialogs.knowledgemanage = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 知识条目管理 ---------- */
        async fetchKnowledgeList(silent) {
            this.knowledgeLoading = true;
            try {
                const r = await API.knowledge.list();
                if (r.status === 200) {
                    this.knowledgeList = r.data || [];
                } else if (!silent) {
                    ElementPlus.ElMessage.error(r.message || '获取知识列表失败');
                }
            } catch (e) {
                if (!silent) ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.knowledgeLoading = false;
            }
        },

        openKnowledgeEdit(item) {
            if (item) {
                this.knowledgeEditForm = { id: item.id, intro: item.intro, content: item.content };
            } else {
                this.knowledgeEditForm = { id: null, intro: '', content: '' };
            }
            this.dialogs.knowledgeedit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        async saveKnowledgeEntry() {
            if (!this.knowledgeEditForm.intro.trim()) {
                ElementPlus.ElMessage.warning('简介不能为空');
                return;
            }
            if (!this.knowledgeEditForm.content.trim()) {
                ElementPlus.ElMessage.warning('内容不能为空');
                return;
            }
            this.saving.knowledgeentry = true;
            try {
                const body = {
                    intro: this.knowledgeEditForm.intro.trim(),
                    content: this.knowledgeEditForm.content.trim(),
                    mode: this.knowledgeEditForm.id ? 'update' : 'add'
                };
                if (this.knowledgeEditForm.id) {
                    body.id = this.knowledgeEditForm.id;
                }
                const r = await API.knowledge.save(body);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.dialogs.knowledgeedit = false;
                    await this.fetchKnowledgeList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving.knowledgeentry = false;
            }
        },

        async confirmDeleteKnowledge(item) {
            try {
                await this.$refs.confirmDialog.show({
                    title: '确认删除',
                    message: '确定要删除知识条目「' + item.intro + '」吗？此操作不可恢复。',
                    confirmText: '确认删除',
                    cancelText: '取消',
                    type: 'warning'
                });
                const r = await API.knowledge.delete(item.id);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('删除成功');
                    await this.fetchKnowledgeList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '删除失败');
                }
            } catch (e) {
                // 用户取消关闭，不做任何处理
                if (e !== undefined && e !== 'cancel' && e !== 'close') {
                    ElementPlus.ElMessage.error('网络请求失败');
                    console.error(e);
                }
            }
        }
    },

    mounted() {
        // 静默加载列表（用于显示条目数量）
        this.fetchKnowledgeList(true);
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
