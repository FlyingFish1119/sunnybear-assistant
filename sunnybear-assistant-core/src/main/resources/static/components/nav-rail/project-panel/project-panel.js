/**
 * 项目面板 —— 双层抽屉（第一层：项目列表 + 就地展开的项目文件 / 项目会话；第二层：文件编辑）
 *
 * 与定时任务 / 记忆 / 知识库同一套门户：导航轨「项目」按钮 → 贴左滑出的抽屉。
 *
 * 数据流：
 *   1) 打开时 GET /chat/project/list 列出所有项目（带 sessionCount 会话数）；
 *   2) 点项目行就地展开，同时拉两样：
 *      · GET /chat/project/file/list?projectId= —— 该项目的文本文件（data/project/{id}/ 下）
 *      · GET /chat/project/sessions?projectId=  —— 该项目下的会话
 *      （每次展开都重新拉，不缓存 —— 文件与会话归属随时可能被别处改掉）；
 *   3) 点会话 → sessionStore.selectSession(session) 在消息区打开，并收起面板；
 *      点文件 → 在第一层右边接着展开第二层抽屉（Ace 全高编辑，与「会话文件」同一套两层门户），
 *      保存走 /chat/project/file/write；点「+」新建也是这一层，头部换成文件名输入框。
 *      Markdown 文件（.md / .markdown）头部多一个「编辑 / 预览」切换：预览走全局 $md 渲染成
 *      只读正文，与会话文件的 Markdown 预览同一套口径（代码高亮 / Mermaid / KaTeX 都一样）。
 *      行的铅笔按钮是「重命名」：走 confirm-dialog 的输入框形态填新文件名，再调
 *      /chat/project/file/rename —— 与会话文件面板同一套语义（编辑内容点行，改名走铅笔）。
 *
 * 项目文件区只做文本文件的增 / 改 / 删：没有拖动、没有文件夹、没有上传。
 * 列表里只认普通文件，目录一律跳过不显示（删除接口那边也只认文件）。
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

/** 扩展名 → ace mode（与 session-files.js 同一套口径；ace 没装对应 mode 的走 text，只影响高亮） */
const PP_ACE_MODES = {
    md: 'markdown', markdown: 'markdown',
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
    ts: 'typescript', tsx: 'typescript', vue: 'html',
    json: 'json', jsonl: 'json',
    html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
    xml: 'xml', svg: 'xml', yaml: 'yaml', yml: 'yaml',
    java: 'java', py: 'python', sql: 'sql', sh: 'sh', bat: 'batchfile',
    properties: 'properties', ini: 'ini', csv: 'text', log: 'text', txt: 'text'
};

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

                        <!-- 展开区：项目文件（上）+ 项目会话（下） -->
                        <div v-if="expandedProjectId === project.id" class="pp-expand">
                            <!-- 项目文件：一条一组「重命名 / 删除」，栏头加号新建；点行本身才是打开编辑器 -->
                            <div class="pp-files">
                                <div class="pp-files-head">
                                    <span class="pp-files-title">项目文件</span>
                                    <button class="pp-item-btn" title="新建文件" @click.stop="openNewFile(project)">
                                        <i data-lucide="plus"></i>
                                    </button>
                                </div>
                                <div v-if="filePageOf(project.id).loading" class="pp-hint">加载文件…</div>
                                <div v-else-if="filePageOf(project.id).list.length === 0" class="pp-hint">
                                    还没有文件，点右上角加号新建一个
                                </div>
                                <template v-else>
                                    <div v-for="f in filePageOf(project.id).list"
                                         :key="f.path"
                                         class="pp-file"
                                         @click="openFile(project, f)">
                                        <span class="pp-file-icon" :style="fileTone(f)"><i :data-lucide="fileIcon(f)"></i></span>
                                        <span class="pp-file-name">{{ f.name }}</span>
                                        <span class="pp-pin"
                                              :class="{ 'is-on': isAutoAppend(project, f) }"
                                              :title="isAutoAppend(project, f) ? '已加入自动追加：这个项目的新会话会带上它' : '点一下加进自动追加'"
                                              @click.stop="toggleAutoAppend(project, f)">
                                            <i data-lucide="pin"></i>
                                        </span>
                                        <span class="pp-file-actions" @click.stop>
                                            <button class="pp-item-btn" title="重命名" @click="renameFile(project, f)">
                                                <i data-lucide="pencil-line"></i>
                                            </button>
                                            <button class="pp-item-btn is-danger" title="删除" @click="deleteFile(project, f)">
                                                <i data-lucide="trash-2"></i>
                                            </button>
                                        </span>
                                    </div>
                                </template>
                            </div>

                            <!-- 项目会话 -->
                            <div class="pp-sessions">
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

        <!-- 第二层：项目文件编辑（在第一层右边接着展开，点文件不收起项目列表） -->
        <aside v-if="fileForm.visible" class="pp-drawer pp-drawer--editor">
            <div class="pp-head">
                <div class="pp-title">
                    <i :data-lucide="fileFormIcon" :style="fileFormTone"></i>
                    <input v-if="fileForm.mode === 'create'"
                           ref="fileNameInput"
                           class="pp-editor-name"
                           v-model="fileForm.path"
                           placeholder="文件名，比如 需求.md"
                           @keydown.enter.prevent="submitFileForm">
                    <span v-else class="pp-title-text">{{ fileForm.path }}</span>
                </div>
                <span v-if="fileSubmitting" class="pp-editor-state">保存中…</span>
                <!-- Markdown 才有的「编辑 / 预览」切换，与会话文件抽屉同一套口径 -->
                <button v-if="canPreview"
                        class="pp-btn"
                        :title="previewMode ? '切回编辑' : '预览 Markdown'"
                        @click="togglePreview">
                    <i :data-lucide="previewMode ? 'pencil-line' : 'eye'"></i>
                </button>
                <button class="pp-btn"
                        :title="fileForm.mode === 'create' ? '新建' : '保存'"
                        :disabled="fileSubmitting"
                        @click="submitFileForm">
                    <i data-lucide="save"></i>
                </button>
                <button class="pp-btn" title="关闭" @click="closeFileForm">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div v-if="fileForm.error" class="pp-editor-error">{{ fileForm.error }}</div>
            <div class="pp-editor-body">
                <!-- 编辑态：切预览时只藏不销毁，切回来光标与未保存的内容都还在 -->
                <div v-show="!previewMode" ref="fileEditorHost" class="pp-editor-host"></div>
                <!-- Markdown 只读预览：渲染编辑器里的当前值，没保存也能看效果 -->
                <div v-if="previewMode" class="pp-preview markdown-body" v-html="previewHtml"></div>
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
            /** projectId → { list, loading } 项目文件（同上，每次展开重新拉） */
            filePages: {},

            /** 图钉正在切换：挡一下连点，免得同一份文件发两次请求 */
            pinBusy: false,

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

            /** 项目文件编辑浮层（文件名 + 内容） */
            fileForm: {
                visible: false,
                mode: 'create',   // create | edit
                projectId: '',
                path: '',
                error: ''
            },
            fileSubmitting: false,

            /** Markdown 预览：只读，内容取自编辑器里的当前值（没保存也能看效果） */
            previewMode: false,
            previewHtml: '',

            /** 换色板：与后端 ChatProjectServiceImplement.PALETTE 是同一套 */
            palette: ProjectColor.PALETTE
        };
    },

    computed: {
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return '';
        },

        /** 当前这份文件是不是 Markdown（只有它才出「编辑 / 预览」按钮） */
        canPreview() {
            if (!this.fileForm.visible) return false;
            return this.isMarkdownName(this.fileForm.path);
        },

        /** 文件编辑抽屉标题的图标与颜色，跟列表行共用 SfFileKind 那一份判定 */
        fileFormIcon() {
            return SfFileKind.iconOf(this.fileForm.path);
        },

        fileFormTone() {
            // 兜底给 inherit 而不是 null：lucide-safe 会把样式搬到 svg 上存成基线，
            // 返回 null 时浏览器那边宿主属性虽然没了，基线里的旧颜色却还留着，
            // 从 .md 改名成 .txt 就能看到蓝色洗不干净。给个合法值让它每轮都被覆盖。
            return { color: SfFileKind.toneOf(this.fileForm.path) || 'inherit' };
        }
    },

    watch: {
        visible(val) {
            this.$emit('visible-change', val);
        },

        /**
         * 文件改名后不再是 Markdown 时，预览态得自己退回来：
         * 否则编辑区被藏着、预览按钮又没了，用户就卡在预览里出不来。
         */
        canPreview(val) {
            if (!val) this.resetPreview();
        }
    },

    methods: {
        /* ==================== 类型图标与配色 ==================== */

        /** 项目文件行的图标与颜色，判定统一走 SfFileKind（会话文件面板用的是同一份） */
        fileIcon(f) {
            return SfFileKind.iconOf(f.name);
        },

        fileTone(f) {
            const tone = SfFileKind.toneOf(f.name);
            return tone ? { color: tone } : null;
        },

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
            // 浮层开着时先收浮层，避免下次打开还挂着半截编辑态（编辑器实例也一并销毁）
            this.form.visible = false;
            this.closeFileForm();
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
                    // 展开中的项目重新拉一遍文件与会话（内容可能刚被改过）
                    if (this.expandedProjectId) {
                        this.loadFiles(this.expandedProjectId);
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

        /* ==================== 展开 / 项目文件 ==================== */

        toggleExpand(project) {
            if (this.expandedProjectId === project.id) {
                this.expandedProjectId = null;
                return;
            }
            // 先收掉可能开着的编辑浮层：换项目了，旧文件不该再挂在那儿
            this.closeFileForm();
            this.expandedProjectId = project.id;
            // 每次都重新拉：文件与会话的归属随时可能被别处改掉，缓存反而容易对不上
            this.filePages[project.id] = { list: [], loading: false };
            this.sessionPages[project.id] = { list: [], loading: false };
            this.loadFiles(project.id);
            this.loadSessions(project.id);
        },

        async loadFiles(projectId) {
            let page = this.filePages[projectId];
            if (!page) {
                page = { list: [], loading: false };
                this.filePages[projectId] = page;
            }
            if (page._inflight) return;   // 同一项目的重复请求直接忽略（展开 + 刷新可能撞上）
            page._inflight = true;
            page.loading = true;
            try {
                const res = await API.projectFile.list(projectId);
                if (res.status === 200) {
                    // 只列普通文件：目录不在这个面板的管辖范围内（没有文件夹概念）
                    page.list = (res.data || []).filter(f => f && !f.directory);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '获取项目文件失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('获取项目文件失败: ' + e.message);
            } finally {
                page._inflight = false;
                page.loading = false;
            }
        },

        /** 取某项目的文件分页状态（未加载时给一个空壳，避免模板里判空） */
        filePageOf(projectId) {
            return this.filePages[projectId] || { list: [], loading: false };
        },

        /* ==================== 项目文件的编辑浮层 ==================== */

        openNewFile(project) {
            this.destroyFileEditor();
            this.resetPreview();
            this.fileForm = {
                visible: true,
                mode: 'create',
                projectId: project.id,
                path: '',
                error: ''
            };
            this.$nextTick(() => {
                this.mountFileEditor('');
                const input = this.$refs.fileNameInput;
                if (input) input.focus();
            });
        },

        async openFile(project, file) {
            if (!file || file.directory) return;
            try {
                const res = await API.projectFile.read(project.id, file.path);
                if (res.status !== 200) {
                    if (window.SbToast) window.SbToast.error(res.message || '读取文件失败');
                    return;
                }
                this.destroyFileEditor();
                this.resetPreview();
                this.fileForm = {
                    visible: true,
                    mode: 'edit',
                    projectId: project.id,
                    path: file.path,
                    error: ''
                };
                this.$nextTick(() => this.mountFileEditor(res.data == null ? '' : res.data));
            } catch (e) {
                if (window.SbToast) window.SbToast.error('读取文件失败: ' + e.message);
            }
        },

        /** 把 Ace 挂到浮层里的编辑宿主上；已有实例先销毁，避免同一个宿主挂两次 */
        mountFileEditor(content) {
            this.destroyFileEditor();
            const host = this.$refs.fileEditorHost;
            if (!host || !window.ace) return;
            // mode / theme 都在本地 lib/ace 下，basePath 必须指过去，
            // 否则 ace 会跑去默认 CDN 找 mode 文件（断网就白屏）
            window.ace.config.set('basePath', API.BASE_PATH + 'lib/ace/');
            const editor = window.ace.edit(host);
            editor.setTheme('ace/theme/textmate');
            editor.session.setMode('ace/mode/' + this.aceMode(this.fileForm.path || ''));
            editor.session.setUseWorker(false);      // 没放 worker 文件，关掉语法检查线程
            editor.session.setUseWrapMode(true);     // 长行折行，别逼人横向拖
            editor.setOptions({
                fontSize: '13px',
                showPrintMargin: false,
                tabSize: 4
            });
            editor.setValue(content == null ? '' : content, -1);
            editor.clearSelection();
            // Ctrl/Cmd+S 立即保存：与会话文件面板用同一套绑定（那边是 mountEditor 里的 addCommand）
            editor.commands.addCommand({
                name: 'saveProjectFile',
                bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
                exec: () => this.saveFileContent()
            });
            this.editor = editor;
        },

        destroyFileEditor() {
            if (this.editor) {
                try {
                    this.editor.destroy();
                } catch (e) {
                    /* 宿主已被移除时 destroy 可能抛错，忽略即可 */
                }
                this.editor = null;
            }
        },

        closeFileForm() {
            this.destroyFileEditor();
            this.fileForm.visible = false;
        },

        /* ==================== Markdown 预览 ==================== */

        /** 切预览：进预览时把编辑器藏起来（不销毁），切回来光标与未保存内容都还在 */
        togglePreview() {
            if (!this.canPreview) return;
            if (this.previewMode) {
                this.previewMode = false;
                // 编辑器藏在 display:none 里量不到尺寸，切回来先让它重新量一次
                this.$nextTick(() => {
                    if (this.editor) {
                        this.editor.resize();
                        this.editor.focus();
                    }
                });
                return;
            }
            this.renderPreview();
            this.previewMode = true;
        },

        /**
         * 渲染的是编辑器里的当前内容：没保存也能看效果，脏标记该怎么亮还怎么亮。
         * <p>走全局 $md（MarkdownUtils），与聊天气泡、Word 预览共用同一套渲染口径
         * （代码高亮 / Mermaid / KaTeX / 原始 HTML 沙盒都在里头）。
         */
        renderPreview() {
            const text = this.editor ? this.editor.getValue() : '';
            this.previewHtml = (this.$md && typeof this.$md.render === 'function')
                ? this.$md.render(text)
                : '';
        },

        /** 换文件 / 关抽屉时把预览态清干净，别把上一份文件的预览留在界面上 */
        resetPreview() {
            this.previewMode = false;
            this.previewHtml = '';
        },

        /** 是不是 Markdown（只有它才出预览按钮；判定与会话文件那边一致） */
        isMarkdownName(name) {
            const dot = String(name || '').lastIndexOf('.');
            const ext = dot < 0 ? '' : String(name).substring(dot + 1).toLowerCase();
            return ext === 'md' || ext === 'markdown';
        },

        /**
         * 存下浮层里的内容，不收浮层：Ctrl+S 与保存按钮共用这一份。
         * <p>新建成功后就地转成编辑态 —— 文件名已经落定，再按 Ctrl+S 走的是写，不会重复创建。
         *
         * @return {Promise<boolean>} 是否存成功，调用方据此决定要不要接着收浮层
         */
        async saveFileContent() {
            if (this.fileSubmitting) return false;   // Ctrl+S 连着敲：上一发还在路上就等它
            const path = (this.fileForm.path || '').trim();
            if (!path) {
                this.fileForm.error = '文件名不能为空';
                return false;
            }
            this.fileSubmitting = true;
            this.fileForm.error = '';
            const content = this.editor ? this.editor.getValue() : '';
            try {
                const res = this.fileForm.mode === 'create'
                    ? await API.projectFile.create({
                        projectId: this.fileForm.projectId, path: path, content: content
                    })
                    : await API.projectFile.write({
                        projectId: this.fileForm.projectId, path: this.fileForm.path, content: content
                    });
                if (res.status === 200) {
                    if (window.SbToast) {
                        window.SbToast.success(this.fileForm.mode === 'create' ? '文件已创建' : '文件已保存');
                    }
                    if (this.fileForm.mode === 'create') this.fileForm.mode = 'edit';
                    await this.loadFiles(this.fileForm.projectId);
                    return true;
                }
                // 同名、超限之类的业务错误直接显示在浮层里，不弹 toast
                this.fileForm.error = res.message || '保存失败';
                return false;
            } catch (e) {
                this.fileForm.error = '保存失败: ' + e.message;
                return false;
            } finally {
                this.fileSubmitting = false;
            }
        },

        /** 保存按钮：存成功就收掉浮层；Ctrl+S 走 saveFileContent，存完接着改 */
        async submitFileForm() {
            if (await this.saveFileContent()) this.closeFileForm();
        },

        /** 这个文件在不在项目的自动追加清单里（清单里的，项目新会话第一条消息会带上） */
        isAutoAppend(project, file) {
            const list = project.autoAppendFiles || [];
            return list.includes(file.name);
        },

        /** 点图钉切换：成功后只改本地这一份清单，不重拉项目列表（让列表安静点） */
        async toggleAutoAppend(project, file) {
            if (this.pinBusy) return;
            this.pinBusy = true;
            const auto = !this.isAutoAppend(project, file);
            try {
                const res = await API.projectFile.autoAppend({
                    projectId: project.id, path: file.name, auto: auto
                });
                if (res.status === 200) {
                    project.autoAppendFiles = (res.data && res.data.autoAppendFiles) || [];
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '切换失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('切换失败: ' + e.message);
            } finally {
                this.pinBusy = false;
            }
        },

        /**
         * 重命名：项目文件只有一层目录，弹个输入框填新文件名就够了，没有路径要拼。
         * <p>正在编辑的就是这份文件时跟着换到新名字上 —— 不然接着按保存，会把旧名字又写回来。
         * <p>钉子（自动追加清单）跟着走：后端已把清单里的旧名换成新名，本地这份同步一下，
         * 免得图钉的亮灭跟真实清单错位。
         */
        async renameFile(project, file) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '重命名',
                    message: '给「' + file.name + '」换一个文件名',
                    confirmText: '确定',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: file.name,
                    inputPlaceholder: '新文件名，比如 需求.md'
                });
            } catch (e) {
                return; // 用户取消
            }
            const newName = String(input || '').trim();
            if (!newName || newName === file.name) return;
            try {
                const res = await API.projectFile.rename({
                    projectId: project.id, path: file.path, newPath: newName
                });
                if (res.status !== 200) {
                    if (window.SbToast) window.SbToast.error(res.message || '重命名失败');
                    return;
                }
                if (window.SbToast) window.SbToast.success('已重命名');
                if (project.autoAppendFiles && project.autoAppendFiles.includes(file.name)) {
                    const next = [];
                    for (const name of project.autoAppendFiles) {
                        const target = name === file.name ? newName : name;
                        if (!next.includes(target)) next.push(target);
                    }
                    project.autoAppendFiles = next;
                }
                if (this.fileForm.visible && this.fileForm.path === file.path) {
                    this.fileForm.path = newName;
                }
                await this.loadFiles(project.id);
            } catch (e) {
                if (window.SbToast) window.SbToast.error('重命名失败: ' + e.message);
            }
        },

        async deleteFile(project, file) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            try {
                await dialog.show({
                    title: '删除文件',
                    message: '确定删除「' + file.name + '」吗？删掉就找不回来了。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return; // 用户取消
            }
            try {
                const res = await API.projectFile.remove({ projectId: project.id, path: file.path });
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('文件已删除');
                    // 后端删文件时顺手摘了自动追加，本地跟着同步，免得图钉还亮着
                    if (project.autoAppendFiles) {
                        project.autoAppendFiles = project.autoAppendFiles.filter(name => name !== file.name);
                    }
                    // 删的正好是浮层里开着的那份，就顺手关掉，别让它停在不存在的内容上
                    if (this.fileForm.visible && this.fileForm.path === file.path) {
                        this.closeFileForm();
                    }
                    await this.loadFiles(project.id);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '删除失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        /* ==================== 项目会话 ==================== */

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

        /* ==================== 新建 / 编辑项目 ==================== */

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
                    const wasCreate = this.form.mode === 'create';
                    this.closeForm();
                    await this.refreshProjects(true);
                    this.notifyChanged();
                    // 新建的项目直接展开：里面已经有一份自动生成的 PROJECT.md 等着写
                    if (wasCreate && res.data && res.data.id) {
                        this.toggleExpand(res.data);
                    }
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
                        ? '「' + project.name + '」手下的 ' + count + ' 个会话会退回普通列表（只解绑，不会被删），项目文件夹里的资料会一并清掉。确定删除吗？'
                        : '确定删除项目「' + project.name + '」吗？项目文件夹里的资料会一并清掉。',
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
                    if (this.expandedProjectId === project.id) {
                        this.closeFileForm();
                        this.expandedProjectId = null;
                    }
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

        aceMode(name) {
            const dot = String(name).lastIndexOf('.');
            const ext = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
            return PP_ACE_MODES[ext] || 'text';
        },

        onKeydown(e) {
            if (!this.visible || e.key !== 'Escape') return;
            if (this.fileForm.visible) {
                this.closeFileForm();
            } else if (this.form.visible) {
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
        // mermaid 异步出图后重渲染，把预览里的源码占位换成 SVG（合并到同一帧）
        if (this.$md && typeof this.$md.onMermaidRendered === 'function') {
            this._offMermaid = this.$md.onMermaidRendered(() => {
                if (this.previewMode) this.renderPreview();
            });
        }
    },

    beforeUnmount() {
        document.removeEventListener('keydown', this.onKeydown);
        if (this._offMermaid) {
            this._offMermaid();
            this._offMermaid = null;
        }
        this.destroyFileEditor();
    },

    updated() {
        this.scheduleIcons();
    }
};

// 自注册进左侧导航轨（registry 定义于 nav-rail.js，须先于本文件加载）
if (typeof RailPlugins !== 'undefined') {
    RailPlugins.register('project', { icon: 'layers', label: '项目', component: ProjectPanel, order: 15 });
}
