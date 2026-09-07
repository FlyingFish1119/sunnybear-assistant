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
                <search-box
                    v-model="searchText"
                    :loading="searching"
                    placeholder="搜索关键词 / 描述"
                    class="glossary-search-box"
                    @search="doSearch"
                ></search-box>
                <div class="glossary-toolbar-actions">
                    <button v-if="!showForm" class="glossary-btn" @click="chooseImportFile"
                        :disabled="importing" title="从 JSON 文件导入词条（重复关键词将被覆盖）">
                        <i data-lucide="upload"></i> {{ importing ? '导入中...' : '导入' }}
                    </button>
                    <button v-if="!showForm && glossaries.length > 0" class="glossary-btn" @click="exportGlossaries"
                        title="导出全部词条为 JSON 文件">
                        <i data-lucide="download"></i> 导出
                    </button>
                    <button v-if="!showForm" class="glossary-btn" @click="openCreate">
                        <i data-lucide="plus"></i> 新增词条
                    </button>
                </div>
            </div>

            <!-- 隐藏的文件选择框，用于导入词条库 JSON -->
            <input type="file" ref="importFile" accept=".json,application/json"
                style="display:none" @change="onImportFile" />

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
                <p>{{ searchText ? '未找到匹配的词条' : '暂无词条，点击上方按钮添加' }}</p>
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
            importing: false,
            searching: false,
            searchText: '',
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
            // PC 端加宽以展示更多词条文本，移动端保持全屏
            return window.innerWidth <= 768 ? '100%' : 'min(1000px, 92vw)';
        }
    },

    methods: {

        /* ========== 打开弹窗 ========== */
        show(characterId, characterName) {
            this.characterId = characterId;
            this.characterName = characterName || '';
            this.searchText = '';
            this.visible = true;
            this.loadGlossaries();
        },

        /* ========== 加载词条列表（打开 / CRUD 刷新） ========== */
        async loadGlossaries() {
            if (!this.characterId) return;
            this.loading = true;
            try {
                this.glossaries = await this._fetchList();
            } catch (e) {
                console.error('加载词条列表失败:', e);
                ElementPlus.ElMessage.error('加载词条列表失败');
                this.glossaries = [];
            } finally {
                this.loading = false;
                this.$nextTick(() => lucide.createIcons());
            }
        },

        /* ========== 搜索词条 ========== */
        /** 有搜索词走后端 search 接口（关键词/描述模糊匹配），否则返回全部 */
        async _fetchList() {
            const q = (this.searchText || '').trim();
            const r = q
                ? await API.character.glossary.search(this.characterId, q)
                : await API.character.glossary.list(this.characterId);
            return r.status === 200 ? (r.data || []) : [];
        },

        /** 搜索框组件防抖后触发（携带关键字），或清空时触发（空串 → 恢复全量） */
        async doSearch() {
            if (!this.characterId) return;
            this.searching = true;
            try {
                this.glossaries = await this._fetchList();
            } catch (e) {
                console.error('搜索词条失败:', e);
            } finally {
                this.searching = false;
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

        /* ========== 导出（当前角色全部词条 → JSON 文件） ========== */
        exportGlossaries() {
            if (!this.glossaries.length) {
                ElementPlus.ElMessage.warning('当前角色没有可导出的词条');
                return;
            }
            const items = this.glossaries.map(g => ({
                keyword: g.keyword,
                desc: g.desc || '',
                content: g.content || ''
            }));
            const blob = new Blob([JSON.stringify(items, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = (this.characterName || this.characterId) + '_词条库_' + this.formatDate(new Date()) + '.json';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            ElementPlus.ElMessage.success('已导出 ' + items.length + ' 条词条');
        },

        formatDate(date) {
            const y = date.getFullYear();
            const m = String(date.getMonth() + 1).padStart(2, '0');
            const d = String(date.getDate()).padStart(2, '0');
            return y + m + d;
        },

        /* ========== 导入 ========== */
        chooseImportFile() {
            this.$refs.importFile.value = '';
            this.$refs.importFile.click();
        },

        async onImportFile(event) {
            const file = event.target.files && event.target.files[0];
            if (!file) return;
            try {
                const text = await file.text();
                let items;
                try {
                    items = JSON.parse(text);
                } catch (e) {
                    ElementPlus.ElMessage.error('文件不是有效的 JSON 格式');
                    return;
                }
                if (!Array.isArray(items)) {
                    ElementPlus.ElMessage.error('JSON 内容应为词条数组，如 [{keyword, desc, content}, ...]');
                    return;
                }
                const valid = items.filter(it => it && it.keyword && it.content);
                if (!valid.length) {
                    ElementPlus.ElMessage.warning('文件中没有有效的词条数据（每条需包含 keyword 和 content）');
                    return;
                }
                const invalidCount = items.length - valid.length;

                // 统计将与现有词条重复（覆盖更新）的数量
                const existingKeywords = new Set(this.glossaries.map(g => g.keyword));
                const duplicateCount = valid.filter(it => existingKeywords.has(it.keyword)).length;

                let message = '共 ' + valid.length + ' 条词条将导入到「'
                    + (this.characterName || this.characterId) + '」'
                    + (duplicateCount > 0 ? '，其中 ' + duplicateCount + ' 条与现有词条关键词重复，将被覆盖更新。' : '。')
                    + (invalidCount > 0 ? '另有 ' + invalidCount + ' 条无效条目将被忽略。' : '')
                    + '是否继续？';
                this.$refs.confirmDialog.show({
                    title: '确认导入',
                    message: message,
                    confirmText: '确认导入',
                    cancelText: '取消',
                    type: 'warning'
                }).then(() => this.doImport(valid)).catch(() => {});
            } catch (e) {
                console.error('读取导入文件失败:', e);
                ElementPlus.ElMessage.error('读取文件失败');
            }
        },

        async doImport(items) {
            this.importing = true;
            try {
                const r = await API.character.glossary.import(this.characterId, items);
                if (r.status === 200 && r.data) {
                    const d = r.data;
                    ElementPlus.ElMessage.success(
                        '导入完成：新增 ' + d.created + ' 条，覆盖 ' + d.updated + ' 条'
                        + (d.failed > 0 ? '，失败 ' + d.failed + ' 条' : '')
                    );
                    await this.loadGlossaries();
                } else {
                    ElementPlus.ElMessage.error(r.message || '导入失败');
                }
            } catch (e) {
                console.error('导入词条失败:', e);
                ElementPlus.ElMessage.error('网络请求失败');
            } finally {
                this.importing = false;
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
            this.importing = false;
            this.searching = false;
            this.searchText = '';
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
