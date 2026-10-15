/**
 * 项目面板 —— 单层抽屉（项目列表 + 就地展开项目会话）
 *
 * 与定时任务 / 记忆 / 知识库同一套门户：导航轨「项目」按钮 → 贴左滑出的抽屉。
 *
 * 数据流：
 *   1) 打开时 GET /chat/project/list 列出所有项目（带 sessionCount 会话数）；
 *   2) 点项目行就地展开，重新拉一次 GET /chat/project/sessions?projectId= 取它的会话
 *      （每次展开都拉，不缓存 —— 会话的归属随时可能被右键菜单改掉）；
 *   3) 点会话 → sessionStore.selectSession(session) 在消息区打开，并收起面板。
 *
 * 与定时任务面板的差别：定时任务的增删改在设置页，而项目拢共就名字 / 描述 / 颜色三样，
 * 为它单开一页设置太重，所以新建与编辑都在这里直接做（抽屉内浮出的小表单）。
 * 删除走 confirm-dialog，文案里明说手下会话只会解绑、不会被删。
 *
 * 项目会话的本质仍是普通会话（type='chat'），只是 extension 里挂了 chat_project_id；
 * 所以同一个会话在左侧会话列表里也能看到，两边点开的是同一个 id。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 公开方法（与其它导轨面板同一约定）：
 *   toggle() — 开 / 关整个面板
 *   open()   — 打开并刷新项目列表
 *   close()  — 关闭面板
 */
const ProjectPanel = {
    name: 'ProjectPanel',

    template: `
    <div v-if="visible" class="pp-overlay" @click.self="close">
        <aside class="pp-drawer">
            <div class="pp-head">
                <div class="pp-title">
                    <i data-lucide="layers"></i>
                    <span class="pp-title-text">项目</span>
                    <span v-if="projects.length" class="pp-count">{{ projects.length }}</span>
                </div>
                <button class="pp-btn" title="新建项目" @click="openCreateForm">
                    <i data-lucide="plus"></i>
                </button>
                <button class="pp-btn" title="刷新" @click="refreshProjects()">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="pp-btn" title="关闭" @click="close">
                    <i data-lucide="x"></i>
                </button>
            </div>

            <div class="pp-body">
                <div v-if="projectsLoading" class="pp-hint">加载中…</div>
                <div v-else-if="projects.length === 0" class="pp-empty">
                    还没有项目<br>项目把围绕同一件事的会话归在一起，点右上角新建
                </div>
                <template v-else>
                    <div v-for="project in projects"
                         :key="project.id"
                         class="pp-item"
                         :class="{ 'is-open': expandedProjectId === project.id }">
                        <!-- 项目行：点整行展开 / 收起 -->
                        <div class="pp-item-head" @click="toggleExpand(project)">
                            <span class="pp-item-bar" :style="{ background: colorOf(project) }"></span>
                            <span class="pp-item-main">
                                <span class="pp-item-title-row">
                                    <span class="pp-item-name">{{ project.name }}</span>
                                    <span class="pp-item-count">{{ project.sessionCount || 0 }} 个会话</span>
                                </span>
                                <span v-if="project.description" class="pp-item-desc">{{ project.description }}</span>
                            </span>
                            <span class="pp-item-actions" @click.stop>
                                <button class="pp-item-btn" title="编辑" @click="openEditForm(project)">
                                    <i data-lucide="pencil-line"></i>
                                </button>
                                <button class="pp-item-btn is-danger" title="删除" @click="deleteProject(project)">
                                    <i data-lucide="trash-2"></i>
                                </button>
                            </span>
                            <span class="pp-item-caret">
                                <i :data-lucide="expandedProjectId === project.id ? 'chevron-down' : 'chevron-right'"></i>
                            </span>
                        </div>

                        <!-- 展开区：该项目下的会话 -->
                        <div v-if="expandedProjectId === project.id" class="pp-sessions">
                            <div v-if="pageOf(project.id).loading" class="pp-hint">加载会话…</div>
                            <div v-else-if="pageOf(project.id).list.length === 0" class="pp-hint">
                                这个项目还没有会话<br>在左侧会话上右键「归入项目」就能放进来
                            </div>
                            <template v-else>
                                <div v-for="s in pageOf(project.id).list"
                                     :key="s.id"
                                     class="pp-session"
                                     @click="openSession(s)">
                                    <span class="pp-session-icon"><i data-lucide="message-square"></i></span>
                                    <span class="pp-session-body">
                                        <span class="pp-session-name">{{ s.name }}</span>
                                        <span class="pp-session-time">{{ formatTime(s.updateTime) }}</span>
                                    </span>
                                    <span class="pp-session-go"><i data-lucide="arrow-right"></i></span>
                                </div>
                            </template>
                        </div>
                    </div>
                </template>
            </div>

            <!-- 新建 / 编辑浮层：名字 + 描述 + 颜色 -->
            <div v-if="form.visible" class="pp-form-overlay" @click.self="closeForm">
                <div class="pp-form">
                    <div class="pp-form-title">{{ form.mode === 'create' ? '新建项目' : '编辑项目' }}</div>

                    <label class="pp-form-label">名称</label>
                    <input class="pp-form-input"
                           v-model="form.name"
                           placeholder="比如：阳阳助手改造"
                           @keydown.enter.prevent="submitForm">

                    <label class="pp-form-label">描述<span class="pp-form-tip">（给 Jev 判断归属用，可留空）</span></label>
                    <textarea class="pp-form-textarea"
                              v-model="form.description"
                              rows="3"
                              placeholder="这个项目是干什么的"></textarea>

                    <label class="pp-form-label">颜色</label>
                    <div class="pp-colors">
                        <button v-for="c in palette"
                                :key="c"
                                class="pp-color"
                                :class="{ 'is-active': form.color === c }"
                                :style="{ background: c }"
                                :title="c"
                                @click="form.color = c"></button>
                    </div>

                    <div v-if="form.error" class="pp-form-error">{{ form.error }}</div>

                    <div class="pp-form-footer">
                        <button class="pp-form-btn" @click="closeForm">取消</button>
                        <button class="pp-form-btn is-primary"
                                :disabled="submitting"
                                @click="submitForm">{{ submitting ? '保存中…' : '保存' }}</button>
                    </div>
                </div>
            </div>
        </aside>
    </div>

    <confirm-dialog ref="confirmDialog"></confirm-dialog>
    `,

    emits: ['visible-change'],

    inject: {
        appSettings: { default: null },
        // 可选注入：插件页没有 store 时降级为只读列表
        sessionStore: { default: null }
    },

    data() {
        return {
            visible: false,
            projects: [],
            projectsLoading: false,
            /** 当前展开的项目 id（同一时刻只展开一个） */
            expandedProjectId: null,
            /** projectId → { list, loading }（每次展开重新拉，不做长期缓存） */
            sessionPages: {},

            /** 新建 / 编辑表单 */
            form: {
                visible: false,
                mode: 'create',   // create | edit
                id: '',
                name: '',
                description: '',
                color: '',
                error: ''
            },
            submitting: false,

            /** 换色板：与后端 ChatProjectServiceImplement.PALETTE 是同一套 */
            palette: ProjectColor.PALETTE
        };
    },

    computed: {
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return '';
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
                this.close();
            } else {
                this.open();
            }
        },

        async open() {
            this.visible = true;
            await this.refreshProjects();
        },

        close() {
            // 表单开着时先收表单，避免下次打开还挂着半截编辑态
            this.form.visible = false;
            this.visible = false;
        },

        /* ==================== 项目列表 ==================== */

        async refreshProjects(silent) {
            this.projectsLoading = true;
            try {
                const res = await API.chatProject.list();
                if (res.status === 200) {
                    this.projects = res.data || [];
                    // 已展开的项目若在别处被删了，顺手收起
                    if (this.expandedProjectId
                        && !this.projects.some(p => p.id === this.expandedProjectId)) {
                        this.expandedProjectId = null;
                    }
                    // 展开中的项目重新拉一遍会话（归属可能刚被改过）
                    if (this.expandedProjectId) {
                        this.loadSessions(this.expandedProjectId);
                    }
                } else if (!silent && window.SbToast) {
                    window.SbToast.error(res.message || '获取项目失败');
                }
            } catch (e) {
                if (!silent && window.SbToast) window.SbToast.error('获取项目失败: ' + e.message);
            } finally {
                this.projectsLoading = false;
            }
        },

        /* ==================== 展开 / 项目会话 ==================== */

        toggleExpand(project) {
            if (this.expandedProjectId === project.id) {
                this.expandedProjectId = null;
                return;
            }
            this.expandedProjectId = project.id;
            // 每次都重新拉：会话归属随时可能被右键菜单改掉，缓存反而容易对不上
            this.sessionPages[project.id] = { list: [], loading: false };
            this.loadSessions(project.id);
        },

        async loadSessions(projectId) {
            let page = this.sessionPages[projectId];
            if (!page) {
                page = { list: [], loading: false };
                this.sessionPages[projectId] = page;
            }
            if (page._inflight) return;   // 同一项目的重复请求直接忽略（展开 + 刷新可能撞上）
            page._inflight = true;
            page.loading = true;
            try {
                const res = await API.chatProject.sessions(projectId);
                if (res.status === 200) {
                    page.list = res.data || [];
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '获取项目会话失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('获取项目会话失败: ' + e.message);
            } finally {
                page._inflight = false;
                page.loading = false;
            }
        },

        /** 取某项目的会话分页状态（未加载时给一个空壳，避免模板里判空） */
        pageOf(projectId) {
            return this.sessionPages[projectId] || { list: [], loading: false };
        },

        /** 点会话：在消息区打开该会话，并收起面板 */
        openSession(session) {
            if (!session || !this.sessionStore) return;
            if (this.sessionStore.sessionSelectLoading) return;
            this.sessionStore.selectSession(session);
            this.close();
        },

        /* ==================== 新建 / 编辑 ==================== */

        openCreateForm() {
            this.form = {
                visible: true,
                mode: 'create',
                id: '',
                name: '',
                description: '',
                color: '',
                error: ''
            };
        },

        openEditForm(project) {
            this.form = {
                visible: true,
                mode: 'edit',
                id: project.id,
                name: project.name || '',
                description: project.description || '',
                color: project.color || '',
                error: ''
            };
        },

        closeForm() {
            this.form.visible = false;
        },

        async submitForm() {
            const name = (this.form.name || '').trim();
            if (!name) {
                this.form.error = '项目名不能为空';
                return;
            }
            this.submitting = true;
            this.form.error = '';
            try {
                const payload = {
                    name: name,
                    description: (this.form.description || '').trim(),
                    color: this.form.color || ''
                };
                let res;
                if (this.form.mode === 'create') {
                    res = await API.chatProject.create(payload);
                } else {
                    payload.id = this.form.id;
                    res = await API.chatProject.update(payload);
                }
                if (res.status === 200) {
                    if (window.SbToast) {
                        window.SbToast.success(this.form.mode === 'create' ? '项目已创建' : '项目已更新');
                    }
                    this.closeForm();
                    await this.refreshProjects(true);
                    this.notifyChanged();
                } else {
                    // 重名之类的业务错误直接显示在表单里，不弹 toast
                    this.form.error = res.message || '保存失败';
                }
            } catch (e) {
                this.form.error = '保存失败: ' + e.message;
            } finally {
                this.submitting = false;
            }
        },

        async deleteProject(project) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            const count = project.sessionCount || 0;
            try {
                await dialog.show({
                    title: '删除项目',
                    message: count > 0
                        ? '「' + project.name + '」手下的 ' + count + ' 个会话会退回普通列表（只解绑，不会被删）。确定删除这个项目吗？'
                        : '确定删除项目「' + project.name + '」吗？',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return; // 用户取消
            }
            try {
                const res = await API.chatProject.remove(project.id);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('项目已删除');
                    if (this.expandedProjectId === project.id) this.expandedProjectId = null;
                    await this.refreshProjects(true);
                    this.notifyChanged();
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '删除失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        /** 通知外面：项目字典变了（会话列表的色条与右键菜单要跟着更新） */
        notifyChanged() {
            if (typeof WsBus !== 'undefined' && WsBus.emit) {
                WsBus.emit('project:changed');
            }
        },

        /* ==================== 辅助 ==================== */

        colorOf(project) {
            return ProjectColor.of(project);
        },

        formatTime(time) {
            if (!time) return '';
            return String(time).replace('T', ' ').substring(0, 16);
        },

        onKeydown(e) {
            if (!this.visible || e.key !== 'Escape') return;
            if (this.form.visible) {
                this.closeForm();
            } else {
                this.close();
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
    },

    updated() {
        this.scheduleIcons();
    }
};

// 自注册进左侧导航轨（registry 定义于 nav-rail.js，须先于本文件加载）
if (typeof RailPlugins !== 'undefined') {
    RailPlugins.register('project', { icon: 'layers', label: '项目', component: ProjectPanel, order: 15 });
}
