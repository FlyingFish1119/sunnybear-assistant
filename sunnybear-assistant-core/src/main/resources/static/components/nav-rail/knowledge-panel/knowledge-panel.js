/**
 * 知识库面板 —— 双层抽屉（条目列表 + 双段编辑器）
 *
 * 与会话文件 / 记忆面板同一套门户：导航轨「知识库」按钮 → 第一层抽屉（条目列表，
 * 贴左侧滑出）→ 点条目 → 第二层抽屉（编辑区，接着往右展开）。
 *
 * 编辑区上下两段，都是 Ace 编辑器：
 *   上 1/4 简介 intro（约 50 字，对话时据此挑选注入）
 *   下 3/4 正文 content
 *
 * 保存全凭手动：点保存按钮或 Ctrl/Cmd+S 落库，打字期间绝不自动保存；
 * 切条目 / 收起 / 关面板时若仍有未保存内容会先落一次，防止白写。
 * 新建走 confirm-dialog 带输入框的形态录入 intro；删除走它的 danger 形态。
 * Esc 依次往回关：先收编辑 → 最后关整个面板。
 *
 * 数据流：全量 list（create_time 倒序）一次拉完，列表展示 intro + 时间。
 *
 * Props:
 *   mainColor — String  主题色（图标与选中底色取它）
 *
 * 公开方法（通过 ref 调用，与 session-files / shell-panel 同一套约定）：
 *   toggle()   — 开 / 关整个面板
 *   open()     — 打开并加载列表
 *   closeAll() — 关闭面板（含第二层）
 *   close()    — 对外统一叫 close，导航轨切换时统一调这个
 */
const KnowledgePanel = {
    name: 'KnowledgePanel',

    template: `
    <div v-if="visible" class="kp-overlay" @click.self="closeAll">
        <!-- 第一层：条目列表 -->
        <aside class="kp-drawer kp-drawer--list">
            <div class="kp-head">
                <div class="kp-title">
                    <i data-lucide="database"></i>
                    <span class="kp-title-text">知识库</span>
                </div>
                <button class="kp-btn" title="新建条目" @click="createEntry">
                    <i data-lucide="file-plus"></i>
                </button>
                <button class="kp-btn" title="刷新" @click="refreshList()">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="kp-btn" title="关闭" @click="closeAll">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="kp-list">
                <div v-if="rootLoading" class="kp-hint">加载中…</div>
                <div v-else-if="list.length === 0" class="kp-empty">
                    知识库还没有条目<br>点右上角「新建条目」建第一个
                </div>
                <template v-else>
                    <div v-for="item in list"
                         :key="item.id"
                         class="kp-row"
                         :class="{ 'is-active': current && current.id === item.id }"
                         :title="item.intro"
                         @click="openEntry(item)">
                        <span class="kp-row-icon"><i data-lucide="file-text"></i></span>
                        <span class="kp-row-body">
                            <span class="kp-row-name">{{ item.intro }}</span>
                            <span class="kp-row-time">{{ formatTime(item.createTime) }}</span>
                        </span>
                        <span class="kp-row-actions" @click.stop>
                            <button class="kp-row-btn is-danger" title="删除" @click="deleteEntry(item)">
                                <i data-lucide="trash-2"></i>
                            </button>
                        </span>
                    </div>
                </template>
            </div>
        </aside>

        <!-- 第二层：双段编辑区（上 intro 1/4，下 content 3/4） -->
        <aside v-if="current" class="kp-drawer kp-drawer--editor">
            <div class="kp-head">
                <div class="kp-title">
                    <i data-lucide="book-open"></i>
                    <span class="kp-title-text">{{ current.intro || '知识条目' }}</span>
                </div>
                <button class="kp-btn" title="根据内容重新生成简介" :disabled="regenLoading" @click="regenIntro">
                    <i data-lucide="sparkles"></i>
                </button>
                <button class="kp-btn" title="保存（Ctrl+S）" :disabled="!dirty || saving" @click="saveNow">
                    <i data-lucide="save"></i>
                </button>
                <button class="kp-btn" title="关闭" @click="closeViewer">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="kp-edit-body">
                <div class="kp-seg kp-seg--intro">
                    <div class="kp-seg-label">简介 intro</div>
                    <div ref="introHost" class="kp-editor-host"></div>
                    <div v-if="regenLoading" class="kp-intro-loading">
                        <span class="kp-spinner"></span>
                        <span>生成简介中…</span>
                    </div>
                </div>
                <div class="kp-seg kp-seg--content">
                    <div class="kp-seg-label">内容 content</div>
                    <div ref="contentHost" class="kp-editor-host"></div>
                </div>
            </div>
            <div class="kp-status">
                <template v-if="dirty || saving || saveFailed">
                    <span class="kp-status-dot" :class="statusClass"></span>
                    <span>{{ statusText }}</span>
                    <span v-if="dirty && !saving" class="kp-status-action" @click="saveNow">立即保存</span>
                </template>
                <template v-else>
                    <span class="kp-status-dot" :class="statusClass"></span>
                    <span>{{ statusText }}</span>
                </template>
                <span class="kp-status-id">#{{ current.id }}</span>
            </div>
        </aside>
    </div>

    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    data() {
        return {
            visible: false,
            list: [],
            rootLoading: false,

            current: null,
            introEditor: null,
            contentEditor: null,
            dirty: false,
            saving: false,
            saveFailed: false,
            savedTip: '',
            regenLoading: false
        };
    },

    computed: {
        statusClass() {
            if (this.saveFailed) return 'is-error';
            if (this.saving) return 'is-saving';
            if (this.dirty) return 'is-dirty';
            return 'is-saved';
        },

        statusText() {
            if (this.saveFailed) return '保存失败';
            if (this.saving) return '保存中…';
            if (this.dirty) return '未保存（Ctrl+S 或点右上角保存）';
            return this.savedTip ? '已保存 ' + this.savedTip : '已保存';
        }
    },

    watch: {
        visible(val) {
            this.$emit('visible-change', val);
        }
    },

    methods: {
        /* ==================== 开合 ==================== */

        toggle() {
            if (this.visible) {
                this.closeAll();
            } else {
                this.open();
            }
        },

        async open() {
            this.visible = true;
            await this.refreshList();
        },

        closeAll() {
            this.closeViewer();
            this.visible = false;
        },

        close() {
            this.closeAll();
        },

        async closeViewer() {
            if (this.dirty) {
                await this.saveNow();
            }
            this.destroyEditors();
            this.current = null;
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
        },

        async refreshList(silent) {
            this.rootLoading = true;
            try {
                const res = await API.knowledge.list();
                if (res.status === 200) {
                    this.list = res.data || [];
                    // 正在编辑的条目若已不在列表（别处删了），顺手收起编辑区
                    if (this.current && !this.list.some(e => e.id === this.current.id)) {
                        await this.closeViewer();
                    }
                } else if (!silent && window.SbToast) {
                    window.SbToast.error(res.message || '获取知识列表失败');
                }
            } catch (e) {
                if (!silent && window.SbToast) window.SbToast.error('获取知识列表失败: ' + e.message);
            } finally {
                this.rootLoading = false;
            }
        },

        /* ==================== 打开条目 / 编辑器 ==================== */

        async openEntry(item) {
            if (this.current && this.current.id === item.id) return;
            if (this.dirty) {
                await this.saveNow();
            }
            this.destroyEditors();
            this.current = { id: item.id, intro: item.intro };
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
            await this.$nextTick();
            this.mountEditor(this.$refs.introHost, item.intro || '', 'intro');
            this.mountEditor(this.$refs.contentHost, item.content || '', 'content');
        },

        mountEditor(host, value, which) {
            if (!host || !window.ace) return;
            window.ace.config.set('basePath', API.BASE_PATH + 'lib/ace/');
            const editor = window.ace.edit(host);
            editor.setTheme('ace/theme/textmate');
            editor.session.setMode('ace/mode/markdown');
            editor.session.setUseWorker(false);
            editor.session.setUseWrapMode(true);
            editor.setOptions({
                fontSize: which === 'intro' ? '13px' : '13px',
                showPrintMargin: false,
                tabSize: 4,
                useSoftTabs: true
            });
            editor.setValue(value, -1);
            editor.clearSelection();
            editor.on('change', this.markDirty);
            editor.commands.addCommand({
                name: 'saveKnowledgeEntry',
                bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
                exec: () => this.saveNow()
            });
            if (which === 'intro') {
                this.introEditor = editor;
            } else {
                this.contentEditor = editor;
            }
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

        markDirty() {
            this.dirty = true;
            this.saveFailed = false;
        },

        /* ==================== 保存 ==================== */

        async saveNow() {
            if (!this.current || !this.dirty || this.saving) return;
            if (!this.introEditor || !this.contentEditor) return;

            const intro = this.introEditor.getValue().trim();
            const content = this.contentEditor.getValue().trim();
            if (!intro) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('简介不能为空');
                return;
            }
            if (!content) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('内容不能为空');
                return;
            }

            this.saving = true;
            try {
                const res = await API.knowledge.save({
                    id: this.current.id,
                    intro: intro,
                    content: content,
                    mode: 'update'
                });
                if (res.status === 200) {
                    this.saveFailed = false;
                    this.savedTip = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                    // 保存期间用户又改了内容 → 保持脏标记
                    if (this.introEditor.getValue().trim() !== intro
                        || this.contentEditor.getValue().trim() !== content) {
                        this.dirty = true;
                    } else {
                        this.dirty = false;
                    }
                    this.current.intro = intro;
                    const target = this.list.find(e => e.id === this.current.id);
                    if (target) {
                        target.intro = intro;
                        target.content = content;
                    }
                } else {
                    this.saveFailed = true;
                    if (window.SbToast) window.SbToast.error(res.message || '保存失败');
                }
            } catch (e) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.error('保存失败: ' + e.message);
            } finally {
                this.saving = false;
            }
        },

        /** 依据当前 content 重新生成 intro，写入编辑器（触发 dirty，需用户确认后保存） */
        async regenIntro() {
            if (this.regenLoading || !this.contentEditor || !this.introEditor) return;
            const content = this.contentEditor.getValue().trim();
            if (!content) {
                if (window.SbToast) window.SbToast.warning('内容不能为空，无法生成简介');
                return;
            }
            this.regenLoading = true;
            this.introEditor.setReadOnly(true);
            try {
                const res = await API.knowledge.introGenerate(content);
                if (res.status === 200 && res.data) {
                    this.introEditor.setValue(res.data, -1);
                    this.introEditor.clearSelection();
                    if (window.SbToast) window.SbToast.success('简介已重新生成，确认后保存');
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '生成简介失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('生成简介失败: ' + e.message);
            } finally {
                if (this.introEditor) this.introEditor.setReadOnly(false);
                this.regenLoading = false;
            }
        },

        /* ==================== 新建 / 删除 ==================== */

        async createEntry() {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '新建知识条目',
                    message: '输入简介（约 50 字，对话时据此挑选注入）',
                    confirmText: '创建',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: '',
                    inputPlaceholder: '知识简介 intro'
                });
            } catch (e) {
                return;
            }
            input = String(input || '').trim();
            if (!input) {
                if (window.SbToast) window.SbToast.warning('简介不能为空');
                return;
            }
            try {
                const res = await API.knowledge.save({
                    intro: input,
                    content: '（在此编辑内容）',
                    mode: 'add'
                });
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已创建');
                    await this.refreshList(true);
                    const created = res.data;
                    if (created) {
                        await this.openEntry(created);
                    }
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '创建失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('创建失败: ' + e.message);
            }
        },

        async deleteEntry(item) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            try {
                await dialog.show({
                    title: '删除条目',
                    message: '确定要删除知识条目「' + item.intro + '」吗？此操作不可恢复。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return;
            }
            try {
                const res = await API.knowledge.delete(item.id);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已删除');
                    if (this.current && this.current.id === item.id) {
                        this.destroyEditors();
                        this.current = null;
                        this.dirty = false;
                        this.saveFailed = false;
                        this.savedTip = '';
                    }
                    await this.refreshList(true);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '删除失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        /* ==================== 辅助 ==================== */

        formatTime(time) {
            if (!time) return '';
            return String(time).replace('T', ' ').substring(5, 16);
        },

        onKeydown(e) {
            if (!this.visible || e.key !== 'Escape') return;
            if (this.current) {
                this.closeViewer();
            } else {
                this.closeAll();
            }
        },

        scheduleIcons() {
            if (this._iconScheduled) return;
            this._iconScheduled = true;
            requestAnimationFrame(() => {
                this._iconScheduled = false;
                if (window.lucide) window.lucide.createIcons();
            });
        }
    },

    mounted() {
        document.addEventListener('keydown', this.onKeydown);
    },

    beforeUnmount() {
        document.removeEventListener('keydown', this.onKeydown);
        this.destroyEditors();
    },

    updated() {
        this.scheduleIcons();
    }
};
