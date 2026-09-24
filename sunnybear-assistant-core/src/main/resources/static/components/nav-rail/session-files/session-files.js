/**
 * 会话文件 / 核心文件组件 —— 双层抽屉（资源树 + 内容区）
 *
 * 双模式：'session' 会话文件（data/session/{id}/file）⇄ 'core' 核心文件（data/core，
 * 跨会话长期保存、随时引用）。无会话时也能打开，但只能看核心文件。
 * 两种模式都支持上传（顶部按钮 / 拖拽进面板，拖到目录行进该目录）；
 * 文件行「加载到发送栏」把快照挂进发送框附件区，「提升为核心」把会话文件转存进核心库。
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

/* 超过该大小走分片断点上传；以下沿用单请求 multipart（受 50MB 限制） */
const SF_LARGE_FILE_THRESHOLD = 32 * 1024 * 1024;
/* 分片大小：8MB，远低于后端 multipart 限制，单片失败只重传这一片 */
const SF_CHUNK_SIZE = 8 * 1024 * 1024;
/* 分片并发数：2 足够打满一般磁盘/带宽，又不至于把小块请求堆成风暴 */
const SF_CHUNK_CONCURRENCY = 2;

const SessionFiles = {
    name: 'SessionFiles',

    template: `
    <div v-if="visible" class="session-files-overlay" @click.self="closeAll">
        <!-- 第一层：资源树（支持拖拽上传：拖到目录行进该目录，拖到空白进根） -->
        <aside class="sf-drawer sf-drawer--tree"
               :class="{ 'is-dragover': dragCounter > 0 }"
               @dragenter.prevent="onDragEnter"
               @dragover.prevent
               @dragleave.prevent="onDragLeave"
               @drop.prevent="onDrop($event, '')">
            <div class="sf-head">
                <div class="sf-title">
                    <i :data-lucide="mode === 'core' ? 'gem' : 'folder-open'"></i>
                    <span class="sf-title-text">{{ mode === 'core' ? '核心文件' : '会话文件' }}</span>
                </div>
                <button class="sf-btn" :title="switchTitle" :disabled="!canSwitch" @click="switchMode">
                    <i data-lucide="arrow-left-right"></i>
                </button>
                <button class="sf-btn" title="上传文件" @click="openUploadPicker">
                    <i data-lucide="upload"></i>
                </button>
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
                        <template v-if="mode === 'core'">
                            核心库还没有文件<br>把重要的文件拖进来长期保存，或点右上角上传
                        </template>
                        <template v-else>
                            这个会话还没有文件<br>AI 生成和上传的文件都会落在这里
                        </template>
                    </div>
                    <div v-for="row in treeRows"
                         :key="row.path"
                         class="sf-tree-row"
                         :class="{ 'is-dir': row.directory, 'is-active': current && current.path === row.path }"
                         :style="{ paddingLeft: (8 + row.depth * 14) + 'px' }"
                         :title="row.path"
                         @click="onRowClick(row)"
                         @dragenter.stop.prevent="onDragEnter"
                         @dragover.stop.prevent
                         @dragleave.stop.prevent="onDragLeave"
                         @drop.stop.prevent="onDrop($event, row.directory ? row.path : parentDir(row.path))">
                        <span class="sf-row-icon"><i :data-lucide="rowIcon(row)"></i></span>
                        <span class="sf-row-name">{{ row.name }}</span>
                        <span class="sf-row-size">{{ row.directory ? '' : formatSize(row.size) }}</span>
                        <span class="sf-row-actions" @click.stop>
                            <button v-if="!row.directory" class="sf-row-btn" title="加载到发送栏" @click="loadToSendbar(row)">
                                <i data-lucide="paperclip"></i>
                            </button>
                            <button v-if="mode === 'session' && !row.directory" class="sf-row-btn" title="提升为核心文件" @click="promoteToCore(row)">
                                <i data-lucide="gem"></i>
                            </button>
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
            <!-- 大文件分片上传进度：进行中可取消，失败/取消可重试（后端已收分片会被跳过） -->
            <div v-if="uploadTasks.length" class="sf-uploads">
                <div v-for="task in uploadTasks" :key="task.id" class="sf-upload">
                    <div class="sf-upload-head">
                        <span class="sf-upload-name" :title="task.name">{{ task.name }}</span>
                        <span class="sf-upload-state" :class="'is-' + task.status">
                            {{ task.status === 'done' ? '完成'
                               : (task.status === 'error' ? '失败'
                               : (task.status === 'canceled' ? '已取消' : task.percent + '%')) }}
                        </span>
                    </div>
                    <div class="sf-upload-bar">
                        <div class="sf-upload-bar-fill" :class="'is-' + task.status"
                             :style="{ width: (task.status === 'done' ? 100 : task.percent) + '%' }"></div>
                    </div>
                    <div class="sf-upload-foot">
                        <span>{{ formatSize(task.loaded) }} / {{ formatSize(task.total) }}</span>
                        <span v-if="task.status === 'uploading'" class="sf-upload-act" @click="cancelUpload(task)">取消</span>
                        <template v-else-if="task.status === 'error' || task.status === 'canceled'">
                            <span class="sf-upload-act" @click="retryUpload(task)">重试</span>
                            <span class="sf-upload-act" @click="dismissUpload(task)">移除</span>
                        </template>
                    </div>
                </div>
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
    <!-- 上传文件的隐藏选择器 -->
    <input ref="uploadInput" type="file" multiple style="display: none" @change="onUploadPicked">
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

            /* 文件模式：'session' 会话文件 | 'core' 核心文件（data/core，跨会话长期保存） */
            mode: 'session',
            /* 拖拽进入计数（子元素 dragenter/leave 触发频繁，用计数维持稳定态） */
            dragCounter: 0,

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
            savedTip: '',

            /* 大文件分片上传任务：{ id, file, dir, name, total, loaded, percent, status, message, cancelled } */
            uploadTasks: []
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

        /** 无会话时锁死在核心文件：不能切到会话文件 */
        canSwitch() {
            return this.mode === 'core' ? !!this.sessionId : true;
        },

        /** 切换按钮的悬浮提示 */
        switchTitle() {
            return this.mode === 'core'
                ? (this.sessionId ? '切换到会话文件' : '没有会话，只能看核心文件')
                : '切换到核心文件';
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
        /* 切会话：会话模式下文件上下文整个变了，直接收起来；核心文件跨会话存在，不用动 */
        sessionId(newId, oldId) {
            if (newId === oldId) return;
            if (this.visible && this.mode === 'session') this.closeAll();
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
            // 无会话也能开：默认核心文件；有会话保持老习惯，默认会话文件
            this.mode = this.sessionId ? 'session' : 'core';
            this.visible = true;
            this.expanded = {};
            this.childrenMap = {};
            await this.loadDir('');
        },

        /** 会话文件 ⇄ 核心文件（无会话时 canSwitch 已挡住） */
        async switchMode() {
            if (!this.canSwitch) return;
            this.closeViewer();
            this.mode = this.mode === 'core' ? 'session' : 'core';
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
            if (this.mode === 'session' && !this.sessionId) return;
            if (dirPath) {
                this.dirLoadingSet = Object.assign({}, this.dirLoadingSet, { [dirPath]: true });
            } else {
                this.rootLoading = true;
            }
            try {
                const res = await this.fileApi().list(dirPath);
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
                this.imageUrl = this.fileApi().rawUrl(row.path);
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
                const res = await this.fileApi().read(path);
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
            if ((this.mode === 'session' && !this.sessionId) || !path) return;
            this.saving = true;
            try {
                const res = await this.fileApi().write(path, content);
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
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
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
                const res = await this.fileApi().create(input);
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
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
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
                const res = await this.fileApi().rename(row.path, input);
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
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
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
                const res = await this.fileApi().remove(row.path);
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
            a.href = this.fileApi().rawUrl(this.current.path);
            a.download = this.current.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        },

        /* ==================== 双模式适配 / 上传 / 引用 ==================== */

        /**
         * 当前模式的文件接口适配层：两种模式方法同构，其余逻辑只认这一层，
         * 切模式不用改任何调用点。会话模式自动带上 sessionId。
         */
        fileApi() {
            const sid = this.sessionId;
            return this.mode === 'core'
                ? {
                    list: (dir) => API.coreFile.list(dir),
                    read: (path) => API.coreFile.read(path),
                    write: (path, content) => API.coreFile.write(path, content),
                    create: (path) => API.coreFile.create(path),
                    rename: (path, newPath) => API.coreFile.rename(path, newPath),
                    remove: (path) => API.coreFile.remove(path),
                    upload: (file, dir) => API.coreFile.upload(file, dir),
                    rawUrl: (path) => API.coreFile.rawUrl(path)
                }
                : {
                    list: (dir) => API.sessionFile.list(sid, dir),
                    read: (path) => API.sessionFile.read(sid, path),
                    write: (path, content) => API.sessionFile.write(sid, path, content),
                    create: (path) => API.sessionFile.create(sid, path),
                    rename: (path, newPath) => API.sessionFile.rename(sid, path, newPath),
                    remove: (path) => API.sessionFile.remove(sid, path),
                    upload: (file, dir) => API.sessionFile.upload(sid, file, dir),
                    rawUrl: (path) => API.sessionFile.rawUrl(sid, path)
                };
        },

        /* ---------- 上传 / 拖拽（两种模式通用） ---------- */

        openUploadPicker() {
            this.$refs.uploadInput.click();
        },

        async onUploadPicked(e) {
            const files = e.target.files;
            e.target.value = '';   // 清空，允许连续选同一个文件
            await this.uploadFiles(files, '');
        },

        onDragEnter() {
            this.dragCounter++;
        },

        onDragLeave() {
            this.dragCounter = Math.max(0, this.dragCounter - 1);
        },

        async onDrop(e, dirPath) {
            this.dragCounter = 0;
            const files = e.dataTransfer && e.dataTransfer.files;
            if (!files || !files.length) return;
            await this.uploadFiles(files, dirPath || '');
        },

        /** 逐个上传到当前模式的指定目录；落盘名以返回为准（同名会自动加序号） */
        async uploadFiles(fileList, dirPath) {
            const files = Array.from(fileList || []);
            if (!files.length) return;
            let okCount = 0;
            for (const file of files) {
                // 大文件走分片断点上传（不受 50MB multipart 限制，可续传）
                if (file.size > SF_LARGE_FILE_THRESHOLD) {
                    const ok = await this.uploadLargeFile(file, dirPath || '');
                    if (ok) okCount++;
                    continue;
                }
                try {
                    const res = await this.fileApi().upload(file, dirPath);
                    if (res.status === 200) {
                        okCount++;
                        await this.refreshPath(res.data);
                    } else if (window.SbToast) {
                        window.SbToast.error((res.message || '上传失败') + '：' + file.name);
                    }
                } catch (e) {
                    if (window.SbToast) window.SbToast.error('上传失败: ' + e.message);
                }
            }
            if (okCount > 0 && window.SbToast) {
                window.SbToast.success('已存入' + (this.mode === 'core' ? '核心库 ' : '会话 ') + okCount + ' 个文件');
            }
        },

        /* ---------- 分片断点上传（大文件） ---------- */

        /** FNV-1a 32 位哈希（带种子），用于从文件元信息生成稳定的 uploadId */
        fnv1a(str, seed) {
            let h = seed >>> 0;
            for (let i = 0; i < str.length; i++) {
                h ^= str.charCodeAt(i);
                h = Math.imul(h, 16777619) >>> 0;
            }
            return h >>> 0;
        },

        /** 稳定 uploadId：同一文件 + 同一目标 → 同一 id，刷新/重试后仍可续传 */
        makeUploadId(file, dirPath) {
            const raw = [this.mode, this.sessionId || '', dirPath || '',
                file.name, file.size, file.lastModified].join('|');
            const a = this.fnv1a(raw, 0x811c9dc5);
            const b = this.fnv1a(raw, 0x01000193);
            return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0');
        },

        /** 新建一条上传任务并返回；同 id 的旧任务先移除（重试场景） */
        beginUploadTask(file, dirPath) {
            const id = this.makeUploadId(file, dirPath);
            const task = {
                id: id,
                file: file,
                dir: dirPath || '',
                name: file.name,
                total: file.size,
                loaded: 0,
                percent: 0,
                status: 'uploading',   // uploading | done | error | canceled
                message: '',
                cancelled: false
            };
            this.uploadTasks = this.uploadTasks.filter(t => t.id !== id);
            this.uploadTasks.push(task);
            // 返回响应式代理而非裸对象，保证后续 task.loaded/percent 的改动能驱动视图
            return this.uploadTasks[this.uploadTasks.length - 1];
        },

        /** 大文件分片上传主流程：init（取断点）→ 补缺口 → complete */
        async uploadLargeFile(file, dirPath) {
            const task = this.beginUploadTask(file, dirPath);
            try {
                const totalChunks = Math.ceil(file.size / SF_CHUNK_SIZE);
                const initRes = await API.upload.init({
                    uploadId: task.id,
                    scope: this.mode,
                    sessionId: this.sessionId || null,
                    dir: task.dir,
                    name: file.name,
                    size: file.size,
                    totalChunks: totalChunks
                });
                if (initRes.status !== 200) throw new Error(initRes.message || '初始化失败');
                const info = initRes.data || {};
                const received = new Set(info.received || []);

                // 断点：已收到的分片计入进度
                task.loaded = 0;
                received.forEach(i => {
                    const start = i * SF_CHUNK_SIZE;
                    task.loaded += Math.min(SF_CHUNK_SIZE, file.size - start);
                });
                task.percent = this.uploadPercent(task);

                const pending = [];
                for (let i = 0; i < totalChunks; i++) {
                    if (!received.has(i)) pending.push(i);
                }
                await this.uploadChunks(task, pending);
                if (task.cancelled) {
                    task.status = 'canceled';
                    task.message = '已取消';
                    return false;
                }

                const doneRes = await API.upload.complete(task.id);
                if (doneRes.status !== 200) throw new Error(doneRes.message || '合并失败');
                task.status = 'done';
                task.percent = 100;
                task.loaded = file.size;
                await this.refreshPath((doneRes.data && doneRes.data.path) || task.dir);
                this.finishUploadTask(task.id);
                return true;
            } catch (e) {
                if (task.cancelled) {
                    task.status = 'canceled';
                    task.message = '已取消';
                } else {
                    task.status = 'error';
                    task.message = e.message || '上传失败';
                    if (window.SbToast) window.SbToast.error('「' + file.name + '」上传失败：' + task.message);
                }
                return false;
            }
        },

        /** 并发上传缺口分片（首个错误即中止其余 worker） */
        async uploadChunks(task, indexes) {
            const file = task.file;
            let cursor = 0;
            let firstError = null;
            const workerCount = Math.max(1, Math.min(SF_CHUNK_CONCURRENCY, indexes.length));
            const workers = [];
            for (let w = 0; w < workerCount; w++) {
                workers.push((async () => {
                    while (!firstError && !task.cancelled) {
                        const pos = cursor++;
                        if (pos >= indexes.length) return;
                        const i = indexes[pos];
                        try {
                            const start = i * SF_CHUNK_SIZE;
                            const end = Math.min(file.size, start + SF_CHUNK_SIZE);
                            const blob = file.slice(start, end);
                            const res = await API.upload.chunk(task.id, i, blob);
                            if (res.status !== 200) throw new Error(res.message || '分片上传失败');
                            task.loaded += blob.size;
                            task.percent = this.uploadPercent(task);
                        } catch (e) {
                            if (!firstError) firstError = e;
                        }
                    }
                })());
            }
            await Promise.all(workers);
            if (firstError) throw firstError;
        },

        uploadPercent(task) {
            if (!task.total) return 0;
            return Math.min(99, Math.round(task.loaded * 100 / task.total));
        },

        /** 完成态任务短暂展示后自动移除；失败/取消态保留供重试或手动移除 */
        finishUploadTask(id) {
            setTimeout(() => {
                this.uploadTasks = this.uploadTasks.filter(t => t.id !== id);
            }, 2500);
        },

        /** 取消上传：worker 轮询 cancelled 标记停下，并通知后端清理暂存分片 */
        cancelUpload(task) {
            if (task.status !== 'uploading') return;
            task.cancelled = true;
            task.status = 'canceled';
            task.message = '已取消';
            API.upload.abort(task.id).catch(() => {});
        },

        /** 重试：清除取消标记后重跑；后端已存分片会被 status 跳过 */
        retryUpload(task) {
            task.cancelled = false;
            task.status = 'uploading';
            task.message = '';
            this.uploadLargeFile(task.file, task.dir);
        },

        /** 从进度列表移除一条（失败/取消态） */
        dismissUpload(task) {
            this.uploadTasks = this.uploadTasks.filter(t => t !== task);
        },

        /* ---------- 加载到发送栏 / 提升为核心 ---------- */

        /** 把文件挂进发送栏附件区：会话文件发链接（勿重复落盘），核心文件发快照 */
        async loadToSendbar(row) {
            if (row.directory) return;
            // 会话文件：发「session-file-link:相对路径」标记，发送时后端直接拼引用，
            // 不会再往会话目录重复写一份（与后端 FileData.SESSION_FILE_LINK_PREFIX 同一约定）
            if (this.mode === 'session') {
                window.dispatchEvent(new CustomEvent('sunnybear:attach-to-sendbar', {
                    detail: { files: [{ name: row.name, data: 'session-file-link:' + row.path }] }
                }));
                if (window.SbToast) window.SbToast.success('「' + row.name + '」已加入发送栏');
                return;
            }
            // 核心文件：快照（base64 data URI），发进会话时固定当时的内容
            try {
                const res = await fetch(this.fileApi().rawUrl(row.path));
                if (!res.ok) throw new Error('HTTP ' + res.status);
                const blob = await res.blob();
                if (blob.size > 50 * 1024 * 1024) {
                    if (window.SbToast) window.SbToast.warning('文件超过 50MB，太大了塞不进发送栏，下载后再处理吧');
                    return;
                }
                const dataUrl = await new Promise((resolve, reject) => {
                    const reader = new FileReader();
                    reader.onload = () => resolve(reader.result);
                    reader.onerror = () => reject(new Error('读取文件失败'));
                    reader.readAsDataURL(blob);
                });
                window.dispatchEvent(new CustomEvent('sunnybear:attach-to-sendbar', {
                    detail: { files: [{ name: row.name, data: dataUrl }] }
                }));
                if (window.SbToast) window.SbToast.success('「' + row.name + '」已加入发送栏');
            } catch (e) {
                if (window.SbToast) window.SbToast.error('加载失败: ' + e.message);
            }
        },

        /** 会话文件转存一份到核心库（提升为核心），原文件保留不动 */
        async promoteToCore(row) {
            if (row.directory || !this.sessionId) return;
            try {
                const res = await API.coreFile.promote(this.sessionId, row.path);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已提升为核心文件：' + res.data);
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '提升失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('提升失败: ' + e.message);
            }
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
