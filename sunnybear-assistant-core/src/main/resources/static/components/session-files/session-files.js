/**
 * 会话文件组件 —— 双层抽屉（资源树 + 内容区）
 *
 * 交互：
 *   导航轨「会话文件」按钮 → 第一层抽屉（资源树，贴左侧滑出）
 *   点文件 → 第二层抽屉（内容区，在第一层右边接着展开），两层可共存
 *   文本 → Ace 编辑器：停输 1.5s 自动落盘，Ctrl/Cmd+S 立即保存
 *   图片 → 直接显示（走 /session/file/raw）
 *   其他 → 提示下载
 *
 * 目录：树形懒加载，点开目录才拉那一层（接口一次只回一层，不递归）。
 * 增删改的二次确认全部走项目自绘的 confirm-dialog（跟会话删除、知识删除同一套视觉）：
 * 删除用 danger 类型；新建 / 改名传 inputValue，走它带输入框的形态。
 *
 * Props:
 *   mainColor — String  主题色（树里目录图标与选中底色取它）
 *
 * Injects:
 *   sessionStore — 会话仓库（取当前会话 ID；插件页没有时为 null）
 *
 * 公开方法（通过 ref 调用）：
 *   toggle()   — 开 / 关整个面板
 *   open()     — 打开并回到根目录
 *   closeAll() — 关闭面板（含内容区）
 */
const SessionFiles = {
    name: 'SessionFiles',

    template: `
    <div v-if="visible" class="session-files-overlay" @click.self="closeAll">
        <!-- 第一层：资源树 -->
        <aside class="sf-drawer sf-drawer--tree">
            <div class="sf-head">
                <div class="sf-title">
                    <i data-lucide="folder-open"></i>
                    <span class="sf-title-text">会话文件</span>
                </div>
                <button class="sf-btn" title="新建文件" @click="createFile">
                    <i data-lucide="file-plus"></i>
                </button>
                <button class="sf-btn" title="刷新" @click="refreshAll">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="sf-btn" title="关闭" @click="closeAll">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="sf-tree">
                <div v-if="rootLoading" class="sf-hint">加载中…</div>
                <template v-else>
                    <div v-if="treeRows.length === 0" class="sf-empty">
                        这个会话还没有文件<br>AI 生成和上传的文件都会落在这里
                    </div>
                    <div v-for="row in treeRows"
                         :key="row.path"
                         class="sf-tree-row"
                         :class="{ 'is-dir': row.directory, 'is-active': current && current.path === row.path }"
                         :style="{ paddingLeft: (8 + row.depth * 14) + 'px' }"
                         :title="row.path"
                         @click="onRowClick(row)">
                        <span class="sf-row-icon"><i :data-lucide="rowIcon(row)"></i></span>
                        <span class="sf-row-name">{{ row.name }}</span>
                        <span class="sf-row-size">{{ row.directory ? '' : formatSize(row.size) }}</span>
                        <span class="sf-row-actions" @click.stop>
                            <button class="sf-row-btn" title="重命名" @click="renameFile(row)">
                                <i data-lucide="pencil-line"></i>
                            </button>
                            <button class="sf-row-btn is-danger" title="删除" @click="deleteFile(row)">
                                <i data-lucide="trash-2"></i>
                            </button>
                        </span>
                    </div>
                    <div v-for="dir in loadingDirs" :key="'loading-' + dir" class="sf-tree-loading">加载中…</div>
                </template>
            </div>
        </aside>

        <!-- 第二层：内容区 -->
        <aside v-if="current" class="sf-drawer sf-drawer--viewer">
            <div class="sf-head">
                <div class="sf-title">
                    <i :data-lucide="rowIcon(current)"></i>
                    <span class="sf-title-text">{{ current.name }}</span>
                </div>
                <button class="sf-btn" title="下载" @click="downloadCurrent">
                    <i data-lucide="download"></i>
                </button>
                <button class="sf-btn" title="关闭" @click="closeViewer">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="sf-viewer-body">
                <div v-if="viewKind === 'text'" ref="editorHost" class="sf-editor-host"></div>
                <div v-else-if="viewKind === 'image'" class="sf-image-wrap">
                    <img :src="imageUrl" :alt="current.name">
                </div>
                <div v-else class="sf-empty">{{ viewerHint }}</div>
            </div>
            <div class="sf-status">
                <template v-if="viewKind === 'text'">
                    <span class="sf-status-dot" :class="statusClass"></span>
                    <span>{{ statusText }}</span>
                </template>
                <span class="sf-status-path" :title="current.path">{{ current.path }}</span>
                <span v-if="viewKind === 'text' && dirty" class="sf-status-action" @click="saveNow">立即保存</span>
            </div>
        </aside>
    </div>

    <!-- 删除确认：走项目自绘的 confirm-dialog，跟会话删除、知识删除那批保持同一套视觉 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    inject: {
        sessionStore: { default: null }
    },

    data() {
        return {
            visible: false,

            /* 目录树：path -> 该层条目数组（'' 表示根目录） */
            childrenMap: {},
            expanded: {},
            dirLoadingSet: {},
            rootLoading: false,

            /* 当前打开的文件 */
            current: null,
            viewKind: '',          // text | image | other
            imageUrl: '',
            viewerHint: '',

            /* 编辑器状态 */
            editor: null,
            dirty: false,
            saving: false,
            saveFailed: false,
            savedTip: ''
        };
    },

    computed: {
        sessionId() {
            const store = this.sessionStore;
            if (!store) return '';
            if (store.currentSessionId) return store.currentSessionId;
            const cur = store.currentSession;
            return (cur && cur.id) || '';
        },

        /** 把树按展开状态拍平成一维行数组（跟 chat-sidebar 的会话行一个路子，递归渲染交给数据） */
        treeRows() {
            const rows = [];
            const walk = (dirPath, depth) => {
                const list = this.childrenMap[dirPath] || [];
                for (const item of list) {
                    rows.push({
                        name: item.name,
                        path: item.path,
                        directory: item.directory,
                        size: item.size,
                        depth: depth
                    });
                    if (item.directory && this.expanded[item.path]) {
                        walk(item.path, depth + 1);
                    }
                }
            };
            walk('', 0);
            return rows;
        },

        loadingDirs() {
            return Object.keys(this.dirLoadingSet).filter((d) => this.dirLoadingSet[d]);
        },

        statusClass() {
            if (this.saveFailed) return 'is-error';
            if (this.saving) return 'is-saving';
            if (this.dirty) return 'is-dirty';
            return 'is-saved';
        },

        statusText() {
            if (this.saveFailed) return '保存失败';
            if (this.saving) return '保存中…';
            if (this.dirty) return '未保存（停手 1.5 秒自动保存）';
            return this.savedTip ? '已保存 ' + this.savedTip : '已保存';
        }
    },

    watch: {
        /* 切会话后文件上下文整个变了，直接收起来，免得看着上一个会话的文件 */
        sessionId(newId, oldId) {
            if (newId === oldId) return;
            if (this.visible) this.closeAll();
        },

        /* 开关状态上报父级：导航轨靠它把高亮对准真正打开的面板 */
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
            if (!this.sessionId) {
                if (window.SbToast) window.SbToast.warning('先选一个会话，再看它的文件');
                return;
            }
            this.visible = true;
            this.expanded = {};
            this.childrenMap = {};
            await this.loadDir('');
        },

        closeAll() {
            this.closeViewer();
            this.visible = false;
        },

        /** 对外统一叫 close，和 shell-panel 对齐（导航轨切换时统一调这个） */
        close() {
            this.closeAll();
        },

        closeViewer() {
            // 关掉之前把没落盘的内容先存了，别让用户白写
            if (this.dirty && this.editor && this.current) {
                this.saveContent(this.editor.getValue(), this.current.path);
            }
            clearTimeout(this._autoSaveTimer);
            this.destroyEditor();
            this.current = null;
            this.viewKind = '';
            this.imageUrl = '';
            this.viewerHint = '';
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
        },

        async refreshAll() {
            this.expanded = {};
            this.childrenMap = {};
            await this.loadDir('');
        },

        /* ==================== 目录树 ==================== */

        async loadDir(dirPath) {
            const sid = this.sessionId;
            if (!sid) return;
            if (dirPath) {
                this.dirLoadingSet = Object.assign({}, this.dirLoadingSet, { [dirPath]: true });
            } else {
                this.rootLoading = true;
            }
            try {
                const res = await API.sessionFile.list(sid, dirPath);
                if (res.status === 200) {
                    this.childrenMap = Object.assign({}, this.childrenMap, { [dirPath]: res.data || [] });
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '列目录失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('列目录失败: ' + e.message);
            } finally {
                const next = Object.assign({}, this.dirLoadingSet);
                delete next[dirPath];
                this.dirLoadingSet = next;
                this.rootLoading = false;
            }
        },

        onRowClick(row) {
            if (row.directory) {
                const next = Object.assign({}, this.expanded);
                if (next[row.path]) {
                    delete next[row.path];
                } else {
                    next[row.path] = true;
                    if (!this.childrenMap[row.path]) this.loadDir(row.path);
                }
                this.expanded = next;
            } else {
                this.openFile(row);
            }
        },

        parentDir(path) {
            const idx = String(path).lastIndexOf('/');
            return idx < 0 ? '' : path.substring(0, idx);
        },

        /** 逐层展开并刷新 path 的祖先目录，保证改完名 / 新建完能立刻看见 */
        async refreshPath(path) {
            const dir = this.parentDir(path);
            const parts = dir ? dir.split('/') : [];
            const next = Object.assign({}, this.expanded);
            let acc = '';
            for (const part of parts) {
                acc = acc ? acc + '/' + part : part;
                next[acc] = true;
                await this.loadDir(acc);
            }
            this.expanded = next;
            await this.loadDir(dir);
        },

        /* ==================== 打开文件 ==================== */

        async openFile(row) {
            // 切文件前先把上一个没保存的落盘
            await this.saveNow();
            this.destroyEditor();
            this.current = { name: row.name, path: row.path, directory: false };
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
            this.viewerHint = '';

            const kind = this.kindOf(row.name);
            this.viewKind = kind;

            if (kind === 'image') {
                this.imageUrl = API.sessionFile.rawUrl(this.sessionId, row.path);
                return;
            }
            if (kind === 'text') {
                await this.loadText(row.path);
                return;
            }
            this.viewerHint = '这个类型没法在线预览，点右上角下载吧';
        },

        async loadText(path) {
            try {
                const res = await API.sessionFile.read(this.sessionId, path);
                if (res.status !== 200) {
                    this.viewKind = 'other';
                    this.viewerHint = res.message || '读取失败';
                    return;
                }
                await this.$nextTick();
                this.mountEditor(res.data || '', path);
            } catch (e) {
                this.viewKind = 'other';
                this.viewerHint = '读取失败: ' + e.message;
            }
        },

        mountEditor(content, path) {
            const host = this.$refs.editorHost;
            if (!host || !window.ace) return;
            // mode / theme 都放在本地 lib/ace 下，basePath 必须指过去，
            // 否则 ace 会跑去默认 CDN 找 mode 文件（断网就白屏）
            window.ace.config.set('basePath', API.BASE_PATH + 'lib/ace/');
            const editor = window.ace.edit(host);
            editor.setTheme('ace/theme/textmate');
            editor.session.setMode('ace/mode/' + this.aceMode(path));
            editor.session.setUseWorker(false);      // 没放 worker 文件，关掉语法检查线程
            editor.session.setUseWrapMode(true);     // 长行折行，别逼人横向拖
            editor.setOptions({
                fontSize: '13px',
                showPrintMargin: false,
                tabSize: 4,
                useSoftTabs: true
            });
            editor.setValue(content, -1);
            editor.clearSelection();
            editor.on('change', this.onEditorChange);
            editor.commands.addCommand({
                name: 'saveSessionFile',
                bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
                exec: () => this.saveNow()
            });
            this.editor = editor;
            this.dirty = false;
            editor.focus();
        },

        destroyEditor() {
            if (this.editor) {
                try {
                    this.editor.destroy();
                } catch (e) {
                    /* 宿主已被移除时 destroy 可能抛错，忽略即可 */
                }
                this.editor = null;
            }
        },

        onEditorChange() {
            this.dirty = true;
            this.saveFailed = false;
            clearTimeout(this._autoSaveTimer);
            this._autoSaveTimer = setTimeout(() => this.saveNow(), 1500);
        },

        /* ==================== 保存 ==================== */

        async saveNow() {
            clearTimeout(this._autoSaveTimer);
            if (!this.current || !this.dirty || this.saving || !this.editor) return;
            await this.saveContent(this.editor.getValue(), this.current.path);
        },

        async saveContent(content, path) {
            const sid = this.sessionId;
            if (!sid || !path) return;
            this.saving = true;
            try {
                const res = await API.sessionFile.write(sid, path, content);
                if (res.status === 200) {
                    this.saveFailed = false;
                    this.savedTip = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                    // 保存期间用户又改了内容 → 保持脏标记，让下一轮自动保存接手
                    if (this.editor && this.current && this.current.path === path
                        && this.editor.getValue() !== content) {
                        this.dirty = true;
                        clearTimeout(this._autoSaveTimer);
                        this._autoSaveTimer = setTimeout(() => this.saveNow(), 1500);
                    } else {
                        this.dirty = false;
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

        /* ==================== 增 / 改名 / 删 ==================== */

        async createFile() {
            const sid = this.sessionId;
            const dialog = this.$refs.confirmDialog;
            if (!sid || !dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '新建文件',
                    message: '文件名可带子目录，例如 tmp/demo.ts',
                    confirmText: '创建',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: 'untitled.txt',
                    inputPlaceholder: '文件名 / 相对路径'
                });
            } catch (e) {
                return;   // 取消
            }
            input = String(input || '').trim();
            if (!input) {
                if (window.SbToast) window.SbToast.warning('文件名不能为空');
                return;
            }
            try {
                const res = await API.sessionFile.create(sid, input);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已创建 ' + input);
                    await this.refreshPath(input);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '创建失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('创建失败: ' + e.message);
            }
        },

        async renameFile(row) {
            const sid = this.sessionId;
            const dialog = this.$refs.confirmDialog;
            if (!sid || !dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '重命名',
                    message: '改名或移动到新的相对路径',
                    confirmText: '确定',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: row.path,
                    inputPlaceholder: '新的文件名 / 相对路径'
                });
            } catch (e) {
                return;   // 取消
            }
            input = String(input || '').trim();
            if (!input || input === row.path) return;
            try {
                const res = await API.sessionFile.rename(sid, row.path, input);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已重命名');
                    // 正在编辑的就是它 → 跟到新路径上，不然继续保存会写回旧名字
                    if (this.current && this.current.path === row.path) {
                        this.current = { name: input.split('/').pop() || input, path: input, directory: false };
                    }
                    await this.refreshPath(row.path);
                    await this.refreshPath(input);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '重命名失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('重命名失败: ' + e.message);
            }
        },

        async deleteFile(row) {
            const sid = this.sessionId;
            const dialog = this.$refs.confirmDialog;
            if (!sid || !dialog) return;
            try {
                await dialog.show({
                    title: row.directory ? '删除目录' : '删除文件',
                    message: '确定要删除「' + row.path + '」吗？此操作不可恢复。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return;   // 取消
            }
            try {
                const res = await API.sessionFile.remove(sid, row.path);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已删除');
                    // 删的正是打开着的文件（或它所在目录）→ 收起内容区
                    if (this.current && (this.current.path === row.path
                        || this.current.path.startsWith(row.path + '/'))) {
                        this.closeViewer();
                    }
                    await this.loadDir(this.parentDir(row.path));
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '删除失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        downloadCurrent() {
            if (!this.current) return;
            const a = document.createElement('a');
            a.href = API.sessionFile.rawUrl(this.sessionId, this.current.path);
            a.download = this.current.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        },

        /* ==================== 类型判定 / 展示辅助 ==================== */

        kindOf(name) {
            const dot = String(name).lastIndexOf('.');
            const ext = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
            if (IMAGE_EXTS.indexOf(ext) >= 0) return 'image';
            if (dot < 0 || TEXT_EXTS.indexOf(ext) >= 0) return 'text';
            return 'other';
        },

        aceMode(name) {
            const dot = String(name).lastIndexOf('.');
            const ext = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
            return ACE_MODES[ext] || 'text';
        },

        rowIcon(row) {
            if (row.directory) return this.expanded[row.path] ? 'folder-open' : 'folder';
            return this.kindOf(row.name) === 'image' ? 'image' : 'file-text';
        },

        formatSize(size) {
            if (size == null || size < 0) return '';
            if (size < 1024) return size + ' B';
            if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
            return (size / 1024 / 1024).toFixed(1) + ' MB';
        },

        onKeydown(e) {
            if (!this.visible || e.key !== 'Escape') return;
            if (this.current) {
                this.closeViewer();
            } else {
                this.closeAll();
            }
        },

        /** lucide 只处理 DOM 里还没替换过的 [data-lucide]，用 rAF 合并频繁刷新 */
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
        clearTimeout(this._autoSaveTimer);
        this.destroyEditor();
    },

    updated() {
        this.scheduleIcons();
    }
};

/* ==================== 扩展名约定 ==================== */

/** 能直接预览的图片类型 */
const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif'];

/** 按文本打开的扩展名；没有扩展名的文件也当文本试（日志、脚本产物多是这样） */
const TEXT_EXTS = [
    'txt', 'md', 'markdown', 'json', 'jsonl', 'csv', 'tsv', 'log', 'ini', 'conf', 'config', 'properties', 'env',
    'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx', 'vue', 'svelte',
    'html', 'htm', 'css', 'scss', 'less', 'xml', 'xsl', 'svg',
    'yml', 'yaml', 'toml',
    'java', 'kt', 'kts', 'groovy', 'gradle', 'py', 'rb', 'php', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs',
    'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'sql', 'pl', 'lua', 'r', 'swift', 'dart', 'scala'
];

/** 扩展名 → ace mode（ace 没装对应 mode 的走 text，纯高亮差异，不影响编辑） */
const ACE_MODES = {
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
    ts: 'typescript', tsx: 'typescript',
    json: 'json', jsonl: 'json',
    html: 'html', htm: 'html', vue: 'html', svelte: 'html',
    css: 'css', scss: 'css', less: 'css',
    xml: 'xml', svg: 'xml', xsl: 'xml',
    md: 'markdown', markdown: 'markdown',
    java: 'java', py: 'python',
    sh: 'sh', bash: 'sh', zsh: 'sh', bat: 'sh', cmd: 'sh',
    sql: 'sql', yml: 'yaml', yaml: 'yaml'
};
