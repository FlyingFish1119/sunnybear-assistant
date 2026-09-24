/**
 * 知识条目管理组件
 *
 * 展示：管理知识条目（条目数量摘要，列表自行加载）
 * 修改：管理对话框内查看全部条目，支持添加、编辑、删除（删除经确认弹窗）
 * 编辑弹窗上下两段 Ace 编辑器：上 1/4 简介 intro、下 3/4 正文 content，
 * 与导航轨知识库面板同一套编辑体验；保存仍走底部按钮（手动保存）
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

        <el-dialog v-model="dialogs.knowledgemanage" title="" width="900px" class="settings-dialog settings-dialog--kb" :close-on-click-modal="false" destroy-on-close @open="fetchKnowledgeList">
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

        <el-dialog v-model="dialogs.knowledgeedit" title="" width="900px" class="settings-dialog settings-dialog--kb kb-edit-dialog" :close-on-click-modal="false" destroy-on-close @open="mountEditors" @closed="destroyEditors">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="book-open" style="width:20px;height:20px"></i>
                    <span>{{ knowledgeEditForm.id ? '编辑知识条目' : '添加知识条目' }}</span>
                </div>
            </template>
            <div class="kb-edit-wrap">
                <div class="kb-edit-seg kb-edit-seg--intro">
                    <div class="kb-edit-label kb-edit-label--row">
                        <span>简介 intro · 约 50 字，对话时据此挑选注入</span>
                        <button type="button" class="kb-regen-btn" :disabled="regenLoading" @click="regenIntro">
                            <i data-lucide="sparkles" style="width:12px;height:12px"></i>
                            {{ regenLoading ? '生成中...' : '重新生成' }}
                        </button>
                    </div>
                    <div ref="introHost" class="kb-edit-host"></div>
                    <div v-if="regenLoading" class="kb-intro-loading">
                        <span class="kb-spinner"></span>
                        <span>生成简介中…</span>
                    </div>
                </div>
                <div class="kb-edit-seg kb-edit-seg--content">
                    <div class="kb-edit-label">内容 content</div>
                    <div ref="contentHost" class="kb-edit-host"></div>
                </div>
            </div>
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
            knowledgeEditForm: { id: null, intro: '', content: '' },
            introEditor: null,
            contentEditor: null,
            regenLoading: false
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

        /* ---------- 双段 Ace 编辑器 ---------- */
        mountEditors() {
            this.destroyEditors();
            if (!window.ace) return;
            // mode/theme 都在本地 lib/ace 下，basePath 必须指过去（断网也不去默认 CDN 找）
            window.ace.config.set('basePath', API.BASE_PATH + 'lib/ace/');
            this.introEditor = this.createEditor(this.$refs.introHost, this.knowledgeEditForm.intro || '');
            this.contentEditor = this.createEditor(this.$refs.contentHost, this.knowledgeEditForm.content || '');
            // 弹窗入场动画结束后宿主尺寸才定型，补一次 resize 免得编辑区留白/裁切
            requestAnimationFrame(() => {
                if (this.introEditor) this.introEditor.resize();
                if (this.contentEditor) this.contentEditor.resize();
            });
        },

        createEditor(host, value) {
            if (!host) return null;
            const editor = window.ace.edit(host);
            editor.setTheme('ace/theme/textmate');
            editor.session.setMode('ace/mode/markdown');
            editor.session.setUseWorker(false);
            editor.session.setUseWrapMode(true);
            editor.setOptions({
                fontSize: '13px',
                showPrintMargin: false,
                tabSize: 4,
                useSoftTabs: true
            });
            editor.setValue(value, -1);
            editor.clearSelection();
            editor.commands.addCommand({
                name: 'saveKnowledgeEntry',
                bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
                exec: () => this.saveKnowledgeEntry()
            });
            return editor;
        },

        destroyEditors() {
            [this.introEditor, this.contentEditor].forEach(editor => {
                if (editor) {
                    try {
                        editor.destroy();
                    } catch (e) {
                        /* 宿主已被移除时 destroy 可能抛错，忽略即可 */
                    }
                }
            });
            this.introEditor = null;
            this.contentEditor = null;
        },

        /** 依据当前 content 重新生成 intro，写入简介编辑器（不自动保存，走底部保存按钮） */
        async regenIntro() {
            if (this.regenLoading || !this.contentEditor || !this.introEditor) return;
            const content = this.contentEditor.getValue().trim();
            if (!content) {
                ElementPlus.ElMessage.warning('内容不能为空，无法生成简介');
                return;
            }
            this.regenLoading = true;
            if (this.introEditor) this.introEditor.setReadOnly(true);
            try {
                const r = await API.knowledge.introGenerate(content);
                if (r.status === 200 && r.data) {
                    this.introEditor.setValue(r.data, -1);
                    this.introEditor.clearSelection();
                    ElementPlus.ElMessage.success('简介已重新生成，确认后保存');
                } else {
                    ElementPlus.ElMessage.error(r.message || '生成简介失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('生成简介失败');
                console.error(e);
            } finally {
                if (this.introEditor) this.introEditor.setReadOnly(false);
                this.regenLoading = false;
            }
        },

        async saveKnowledgeEntry() {
            // 编辑器在场时以编辑器内容为准（v-model 不经过 Ace，需要手动回读）
            if (this.introEditor) {
                this.knowledgeEditForm.intro = this.introEditor.getValue();
            }
            if (this.contentEditor) {
                this.knowledgeEditForm.content = this.contentEditor.getValue();
            }
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

    beforeUnmount() {
        this.destroyEditors();
    },

    mounted() {
        // 静默加载列表（用于显示条目数量）
        this.fetchKnowledgeList(true);
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
