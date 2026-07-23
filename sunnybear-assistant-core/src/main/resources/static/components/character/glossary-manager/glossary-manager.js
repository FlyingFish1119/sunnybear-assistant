/*
 * @Usage 角色词条管理组件 —— 弹窗展示词条列表，支持 CRUD，卡片式布局
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

const GlossaryManager = {
    name: 'GlossaryManager',
    template: `
    <el-dialog v-model="visible" :title="dialogTitle" :width="dialogWidth"
        class="glossary-dialog" :close-on-click-modal="false" destroy-on-close
        @closed="onClosed">
        <template #header>
            <div class="dialog-header-wrap">
                <i data-lucide="book-open" style="width:20px;height:20px"></i>
                <span>词条管理</span>
                <span v-if="characterName" style="font-weight:400;color:var(--text-muted);font-size:13px;margin-left:4px">
                    - {{ characterName }}
                </span>
            </div>
        </template>

        <!-- 加载中 -->
        <div v-if="loading" class="glossary-loading">
            <p>加载中...</p>
        </div>

        <template v-else>
            <!-- 工具栏 -->
            <div class="glossary-toolbar">
                <span class="glossary-count">共 {{ glossaries.length }} 条词条</span>
                <button v-if="!showForm" class="glossary-btn" @click="openCreate">
                    <i data-lucide="plus"></i> 新增词条
                </button>
            </div>

            <!-- 新增/编辑表单 -->
            <div v-if="showForm" class="glossary-form-wrap">
                <div class="glossary-form-row">
                    <div class="glossary-form-field field-keyword">
                        <label>关键词 <span style="color:#f56c6c">*</span></label>
                        <input v-model="form.keyword" placeholder="如：角色名、地名、术语"
                            maxlength="200" @keyup.enter="submitForm" ref="keywordInput" />
                    </div>
                    <div class="glossary-form-field field-desc">
                        <label>描述（简述）</label>
                        <input v-model="form.desc" placeholder="简短的描述，会注入系统提示词" maxlength="500" />
                    </div>
                </div>
                <div class="glossary-form-row">
                    <div class="glossary-form-field field-content">
                        <label>内容 <span style="color:#f56c6c">*</span></label>
                        <textarea v-model="form.content" placeholder="词条的完整内容，AI 通过工具查询时会返回此内容"
                            rows="3"></textarea>
                    </div>
                </div>
                <div class="glossary-form-actions">
                    <button class="glossary-btn glossary-btn-sm" @click="cancelForm">取消</button>
                    <button class="glossary-btn glossary-btn-sm" @click="submitForm" :disabled="saving">
                        {{ saving ? '保存中...' : (editingId ? '更新' : '创建') }}
                    </button>
                </div>
            </div>

            <!-- 空状态 -->
            <div v-if="!loading && glossaries.length === 0 && !showForm" class="glossary-empty">
                <i data-lucide="file-text"></i>
                <p>暂无词条，点击上方按钮添加</p>
            </div>

            <!-- 卡片列表 -->
            <div v-if="glossaries.length > 0" class="glossary-cards">
                <div v-for="g in glossaries" :key="g.id" class="glossary-card">
                    <div class="card-header">
                        <span class="card-keyword">{{ g.keyword }}</span>
                        <div class="card-actions">
                            <button class="glossary-btn glossary-btn-sm" @click="openEdit(g)" title="编辑">
                                <i data-lucide="pencil"></i>
                            </button>
                            <button class="glossary-btn glossary-btn-sm glossary-btn-danger"
                                @click="confirmDelete(g)" title="删除">
                                <i data-lucide="trash-2"></i>
                            </button>
                        </div>
                    </div>
                    <div v-if="g.desc" class="card-desc">{{ g.desc }}</div>
                    <div class="card-content">{{ g.content }}</div>
                </div>
            </div>
        </template>

        <template #footer>
            <div class="dialog-footer">
                <button class="dialog-btn dialog-btn-cancel" @click="visible = false">关闭</button>
            </div>
        </template>
    </el-dialog>

    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    data() {
        return {
            visible: false,
            loading: false,
            saving: false,
            characterId: '',
            characterName: '',
            glossaries: [],
            showForm: false,
            editingId: null,
            form: { keyword: '', desc: '', content: '' }
        };
    },

    computed: {
        dialogTitle() {
            return this.characterName
                ? '词条管理 - ' + this.characterName
                : '词条管理';
        },
        dialogWidth() {
            return window.innerWidth <= 768 ? '100%' : '768px';
        }
    },

    methods: {

        /* ========== 打开弹窗 ========== */
        show(characterId, characterName) {
            this.characterId = characterId;
            this.characterName = characterName || '';
            this.visible = true;
            this.loadGlossaries();
        },

        /* ========== 加载词条列表 ========== */
        async loadGlossaries() {
            if (!this.characterId) return;
            this.loading = true;
            try {
                const r = await API.character.glossary.list(this.characterId);
                if (r.status === 200) {
                    this.glossaries = r.data || [];
                } else {
                    this.glossaries = [];
                }
            } catch (e) {
                console.error('加载词条列表失败:', e);
                ElementPlus.ElMessage.error('加载词条列表失败');
                this.glossaries = [];
            } finally {
                this.loading = false;
                this.$nextTick(() => lucide.createIcons());
            }
        },

        /* ========== 创建 ========== */
        openCreate() {
            this.showForm = true;
            this.editingId = null;
            this.form = { keyword: '', desc: '', content: '' };
            this.$nextTick(() => {
                const inp = this.$refs.keywordInput;
                if (inp) inp.focus();
                lucide.createIcons();
            });
        },

        /* ========== 编辑 ========== */
        openEdit(glossary) {
            this.showForm = true;
            this.editingId = glossary.id;
            this.form = {
                keyword: glossary.keyword,
                desc: glossary.desc || '',
                content: glossary.content || ''
            };
            this.$nextTick(() => {
                const inp = this.$refs.keywordInput;
                if (inp) inp.focus();
                lucide.createIcons();
            });
        },

        cancelForm() {
            this.showForm = false;
            this.editingId = null;
            this.form = { keyword: '', desc: '', content: '' };
            this.$nextTick(() => lucide.createIcons());
        },

        /* ========== 提交表单 ========== */
        async submitForm() {
            const keyword = (this.form.keyword || '').trim();
            const content = (this.form.content || '').trim();

            if (!keyword) {
                ElementPlus.ElMessage.warning('请输入关键词');
                return;
            }
            if (!content) {
                ElementPlus.ElMessage.warning('请输入词条内容');
                return;
            }

            this.saving = true;
            try {
                let r;
                if (this.editingId) {
                    r = await API.character.glossary.update({
                        id: this.editingId,
                        keyword: keyword,
                        desc: (this.form.desc || '').trim(),
                        content: content
                    });
                } else {
                    r = await API.character.glossary.create({
                        characterId: this.characterId,
                        keyword: keyword,
                        desc: (this.form.desc || '').trim(),
                        content: content
                    });
                }

                if (r.status === 200) {
                    ElementPlus.ElMessage.success(this.editingId ? '词条已更新' : '词条已创建');
                    this.cancelForm();
                    await this.loadGlossaries();
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                console.error('保存词条失败:', e);
                ElementPlus.ElMessage.error('网络请求失败');
            } finally {
                this.saving = false;
            }
        },

        /* ========== 删除 ========== */
        confirmDelete(glossary) {
            this.$refs.confirmDialog.show({
                title: '确认删除',
                message: '确定要删除词条「' + glossary.keyword + '」吗？此操作不可恢复。',
                confirmText: '确认删除',
                cancelText: '取消',
                type: 'danger'
            }).then(() => this.doDelete(glossary.id)).catch(() => {});
        },

        async doDelete(id) {
            try {
                const r = await API.character.glossary.delete(id);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('词条已删除');
                    await this.loadGlossaries();
                } else {
                    ElementPlus.ElMessage.error(r.message || '删除失败');
                }
            } catch (e) {
                console.error('删除词条失败:', e);
                ElementPlus.ElMessage.error('网络请求失败');
            }
        },

        /* ========== 关闭弹窗 ========== */
        onClosed() {
            this.characterId = '';
            this.characterName = '';
            this.glossaries = [];
            this.showForm = false;
            this.editingId = null;
            this.form = { keyword: '', desc: '', content: '' };
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
