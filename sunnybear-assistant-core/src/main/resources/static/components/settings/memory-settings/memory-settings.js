/**
 * 记忆管理组件
 *
 * 展示：管理记忆（记忆数量摘要，列表自行加载）
 * 修改：管理对话框内查看全部记忆，支持添加、编辑、删除（删除经确认弹窗）
 * 添加 / 编辑 / 删除后自行刷新列表
 */
const MemorySettings = {
    name: 'MemorySettings',

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
                    <span class="settings-item-label">管理记忆</span>
                    <span class="settings-item-desc">共 {{ memoryList.length }} 条记忆 · 添加、编辑、删除</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ memoryList.length }} 条</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.memorymanage" title="" width="800px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close @open="fetchMemoryList">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="brain" style="width:20px;height:20px"></i>
                    <span>记忆管理</span>
                </div>
            </template>
            <div style="margin-bottom:16px;display:flex;justify-content:flex-end">
                <button type="button" class="dialog-btn dialog-btn-save" @click="openMemoryEdit(null)" style="padding:6px 16px;font-size:13px">
                    <i data-lucide="plus" style="width:14px;height:14px"></i> 添加记忆
                </button>
            </div>
            <div v-if="memoryLoading" style="text-align:center;padding:40px;color:#909399">加载中...</div>
            <div v-else-if="memoryList.length === 0" style="text-align:center;padding:40px;color:#909399">暂无记忆，点击上方按钮添加</div>
            <div v-else class="entry-list">
                <div v-for="item in memoryList" :key="item.id" class="entry-item">
                    <div class="entry-item-body">
                        <div class="entry-item-content">{{ item.content }}</div>
                        <div class="entry-item-time">{{ formatTime(item.createTime) }}</div>
                    </div>
                    <div class="entry-item-actions">
                        <button type="button" class="entry-action-btn edit" @click="openMemoryEdit(item)" title="编辑">
                            <i data-lucide="pencil" style="width:15px;height:15px"></i>
                        </button>
                        <button type="button" class="entry-action-btn delete" @click="confirmDeleteMemory(item)" title="删除">
                            <i data-lucide="trash-2" style="width:15px;height:15px"></i>
                        </button>
                    </div>
                </div>
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.memorymanage = false">关闭</button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogs.memoryedit" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="brain" style="width:20px;height:20px"></i>
                    <span>{{ memoryEditForm.id ? '编辑记忆' : '添加记忆' }}</span>
                </div>
            </template>
            <el-form :model="memoryEditForm" label-width="80px" label-position="left">
                <el-form-item label="内容">
                    <textarea class="settings-textarea" v-model="memoryEditForm.content" rows="6" placeholder="输入记忆内容"></textarea>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.memoryedit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveMemoryEntry" :disabled="saving.memoryentry">
                        <span v-if="saving.memoryentry" class="btn-spinner"></span>
                        <span>{{ saving.memoryentry ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>

        <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    </div>`,

    data() {
        return {
            dialogs: { memorymanage: false, memoryedit: false },
            memoryList: [],
            memoryLoading: false,
            memoryEditForm: { id: null, content: '' }
        };
    },

    methods: {
        openManage() {
            this.dialogs.memorymanage = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 记忆管理 ---------- */
        async fetchMemoryList(silent) {
            this.memoryLoading = true;
            try {
                const r = await API.memory.list();
                if (r.status === 200) {
                    this.memoryList = r.data || [];
                } else if (!silent) {
                    ElementPlus.ElMessage.error(r.message || '获取记忆列表失败');
                }
            } catch (e) {
                if (!silent) ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.memoryLoading = false;
            }
        },

        openMemoryEdit(item) {
            if (item) {
                this.memoryEditForm = { id: item.id, content: item.content };
            } else {
                this.memoryEditForm = { id: null, content: '' };
            }
            this.dialogs.memoryedit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        async saveMemoryEntry() {
            if (!this.memoryEditForm.content.trim()) {
                ElementPlus.ElMessage.warning('内容不能为空');
                return;
            }
            this.saving.memoryentry = true;
            try {
                const body = {
                    content: this.memoryEditForm.content.trim(),
                    mode: this.memoryEditForm.id ? 'update' : 'add'
                };
                if (this.memoryEditForm.id) {
                    body.id = this.memoryEditForm.id;
                }
                const r = await API.memory.save(body);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.dialogs.memoryedit = false;
                    await this.fetchMemoryList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving.memoryentry = false;
            }
        },

        async confirmDeleteMemory(item) {
            try {
                await this.$refs.confirmDialog.show({
                    title: '确认删除',
                    message: '确定要删除这条记忆吗？此操作不可恢复。',
                    confirmText: '确认删除',
                    cancelText: '取消',
                    type: 'warning'
                });
                const r = await API.memory.delete(item.id);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('删除成功');
                    await this.fetchMemoryList();
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
        this.fetchMemoryList(true);
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
