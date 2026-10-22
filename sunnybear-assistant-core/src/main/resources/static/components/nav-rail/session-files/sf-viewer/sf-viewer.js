/**
 * 会话文件 · 二级抽屉（文件内容区）
 *
 * 从 session-files.js 拆出来的子组件，只归会话文件模块用：由 session-files.js 局部注册，
 * 不进 index.html 的全局组件表，别的模块也不会顺手复用它。
 *
 * 职责就是「打开一个文件看内容」这一层：
 *   文本     → Ace 编辑器（停输 1.5 秒自动落盘，Ctrl/Cmd+S 立即保存）
 *   Markdown → 头部多一个「编辑 / 预览」切换，预览走全局 $md（MarkdownUtils）渲染成只读正文
 *   Word     → .docx 交给 mammoth 在浏览器端解析成 HTML 只读预览（老的二进制 .doc 认不了，只能下载）
 *   PDF      → 内嵌浏览器自带的阅读器（iframe 直接指 raw 接口，零解析、不加载任何库）
 *   图片     → 直接显示（走 api.rawUrl）
 *   视频     → 内嵌播放器（同一个 raw 接口，靠 Range 支持拖动进度；浏览器不认的封装格式只能下载看）
 *   其他     → 提示下载
 *
 * 抽屉的公共件（.sf-drawer / .sf-head / .sf-btn / 宽度变量 --sf-viewer-w）仍在
 * session-files.css 里，两个文件同属一个模块、同页加载，样式互相接得上。
 *
 * Props:
 *   file — Object | null  要看的文件 { name, path }；给 null 即收起抽屉
 *   api  — Object         文件接口 { read, write, rawUrl }，由父组件的 fileApi() 传进来
 *                         （会话模式 / 核心模式各自的接口，组件不关心区别）
 *
 * Emits:
 *   close — 请求收起抽屉（父组件负责把 file 置 null）
 *
 * 依赖全局：$md（MarkdownUtils）、lucide、SbToast、ace、mammoth
 */

/* ==================== 扩展名约定（会话文件模块共用） ==================== */

/**
 * 文件类型判定与 Ace 语言映射。
 * <p>会话文件的两半都用它：资源树按它选图标，内容区按它决定怎么打开，
 * 所以跟着内容区放在这里，由会话文件组件加载时先定义。
 */
const SfFileKind = {

    /** 能直接预览的图片类型 */
    IMAGE_EXTS: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif'],

    /**
     * 能内嵌播放的视频类型。
     * 只列浏览器真认的封装（mp4 / webm 是主路，mov / ogv 看浏览器脸色）；
     * mkv / avi / wmv / flv 一律不进 —— 后端不给它们兜底 MIME，播放器也多半放不出来，走下载更省事。
     */
    VIDEO_EXTS: ['mp4', 'm4v', 'webm', 'ogv', 'mov'],

    /**
     * 能转 HTML 预览的 Word 文档。
     * 只有 OOXML 的 .docx —— mammoth 就吃这一种；老的二进制 .doc 解析不了，落回「other」走下载。
     */
    DOCX_EXTS: ['docx'],

    /**
     * 交给浏览器自带阅读器的 PDF。
     * 纯前端零解析：后端 raw 接口回的是 application/pdf，浏览器自己接管，
     * 顺带还吃 Range（翻到后面不会整份重下）。
     */
    PDF_EXTS: ['pdf'],

    /* ---------- 下面几组清单只服务「图标 + 类型色」，不参与「怎么打开」的判定 ---------- */

    /** 音频 */
    AUDIO_EXTS: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'oga', 'm4a', 'wma', 'opus', 'ape'],

    /** 表格 */
    SHEET_EXTS: ['csv', 'tsv', 'xlsx', 'xls', 'ods'],

    /** 压缩包 / 归档（jar、war 本质上也是 zip，一并归进来） */
    ARCHIVE_EXTS: ['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz', 'zst', 'jar', 'war', 'iso'],

    /** 数据库文件与 SQL 脚本 */
    DB_EXTS: ['db', 'sqlite', 'sqlite3', 'sql', 'mdb'],

    /** JSON 系（单独拎出来，跟一般源码分开认） */
    JSON_EXTS: ['json', 'jsonl', 'ndjson', 'geojson'],

    /** Markdown：图标与彩色都单独一档，跟纯文本区分开 */
    MARKDOWN_EXTS: ['md', 'markdown'],

    /** 配置文件 */
    CONFIG_EXTS: ['yml', 'yaml', 'toml', 'ini', 'conf', 'config', 'properties', 'env', 'lock', 'editorconfig'],

    /** 脚本 */
    SCRIPT_EXTS: ['sh', 'bash', 'zsh', 'fish', 'bat', 'cmd', 'ps1', 'psm1', 'vbs'],

    /** 源码 */
    CODE_EXTS: [
        'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx', 'vue', 'svelte',
        'html', 'htm', 'css', 'scss', 'less', 'sass',
        'xml', 'xsl', 'xsd',
        'java', 'kt', 'kts', 'groovy', 'gradle', 'scala',
        'py', 'rb', 'php', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cc', 'cs',
        'swift', 'dart', 'lua', 'pl', 'r'
    ],

    /**
     * 类型 → lucide 图标名。名字都核对过本地 lucide.min.js，不会画出空白。
     * <p>Word 和纯文本共用 file-text 是故意的：lucide 没有 file-word / file-pdf 这号图标，
     * 靠类型色（红 / 蓝 / 灰）分就够了，不必为了凑图标去加重依赖。
     */
    FILE_ICONS: {
        image: 'file-image',
        video: 'file-video',
        audio: 'file-audio',
        pdf: 'file-type',
        word: 'file-text',
        markdown: 'book-open-text',
        sheet: 'file-spreadsheet',
        json: 'file-json',
        code: 'file-code',
        config: 'file-cog',
        script: 'file-terminal',
        archive: 'file-archive',
        database: 'database',
        text: 'file-text',
        other: 'file'
    },

    /**
     * 类型色板：全部压过饱和度，浅底上认得出、又不至于把列表刷成调色盘。
     * <p>纯文本与未知类型故意不给色 —— 它们占绝大多数，满屏抢眼反而看不清重点。
     */
    FILE_TONES: {
        image: '#5f9a7a',
        video: '#8b78c8',
        audio: '#c07a9c',
        pdf: '#c96a6a',
        word: '#7a86b8',
        markdown: '#4f9aa8',
        sheet: '#5a9e7a',
        json: '#b3924a',
        code: '#5b8ac4',
        config: '#7d8f7d',
        script: '#8a7d6a',
        archive: '#c08a3e',
        database: '#7a8fa8'
    },

    /** 按文本打开的扩展名；没有扩展名的文件也当文本试（日志、脚本产物多是这样） */
    TEXT_EXTS: [
        'txt', 'md', 'markdown', 'json', 'jsonl', 'csv', 'tsv', 'log', 'ini', 'conf', 'config', 'properties', 'env',
        'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx', 'vue', 'svelte',
        'html', 'htm', 'css', 'scss', 'less', 'xml', 'xsl', 'svg',
        'yml', 'yaml', 'toml',
        'java', 'kt', 'kts', 'groovy', 'gradle', 'py', 'rb', 'php', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs',
        'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'sql', 'pl', 'lua', 'r', 'swift', 'dart', 'scala'
    ],

    /** 扩展名 → ace mode（ace 没装对应 mode 的走 text，纯高亮差异，不影响编辑） */
    ACE_MODES: {
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
    },

    /** 取小写扩展名（文件名里没有点则返回空串） */
    extOf(name) {
        const dot = String(name || '').lastIndexOf('.');
        return dot < 0 ? '' : String(name).substring(dot + 1).toLowerCase();
    },

    /** 归类：image | video | docx | pdf | text | other —— 没有扩展名的按文本试 */
    of(name) {
        const ext = this.extOf(name);
        if (this.IMAGE_EXTS.indexOf(ext) >= 0) return 'image';
        if (this.VIDEO_EXTS.indexOf(ext) >= 0) return 'video';
        if (this.DOCX_EXTS.indexOf(ext) >= 0) return 'docx';
        if (this.PDF_EXTS.indexOf(ext) >= 0) return 'pdf';
        if (!ext || this.TEXT_EXTS.indexOf(ext) >= 0) return 'text';
        return 'other';
    },

    /** 是不是 Markdown（只有它才出预览按钮，判定与类型归类一致） */
    isMarkdown(name) {
        const ext = this.extOf(name);
        return ext === 'md' || ext === 'markdown';
    },

    /** 扩展名 → ace mode */
    aceModeOf(name) {
        return this.ACE_MODES[this.extOf(name)] || 'text';
    },

    /**
     * 图标大类：比 of() 细一层，专门回答「这文件该长什么样」。
     * <p>of() 只管「怎么打开」（预览 / 下载 / 编辑），这里只管「长什么样」，
     * 两套清单各管各的 —— 想给某类文件换外观，动这里，别去碰 of()。
     * 顺序即优先级，从上往下第一个命中的就是归属。
     */
    fileCategoryOf(name) {
        const ext = this.extOf(name);
        if (this.IMAGE_EXTS.indexOf(ext) >= 0) return 'image';
        if (this.VIDEO_EXTS.indexOf(ext) >= 0) return 'video';
        if (this.AUDIO_EXTS.indexOf(ext) >= 0) return 'audio';
        if (this.PDF_EXTS.indexOf(ext) >= 0) return 'pdf';
        if (this.DOCX_EXTS.indexOf(ext) >= 0) return 'word';
        if (this.MARKDOWN_EXTS.indexOf(ext) >= 0) return 'markdown';
        if (this.SHEET_EXTS.indexOf(ext) >= 0) return 'sheet';
        if (this.ARCHIVE_EXTS.indexOf(ext) >= 0) return 'archive';
        if (this.DB_EXTS.indexOf(ext) >= 0) return 'database';
        if (this.JSON_EXTS.indexOf(ext) >= 0) return 'json';
        if (this.CONFIG_EXTS.indexOf(ext) >= 0) return 'config';
        if (this.SCRIPT_EXTS.indexOf(ext) >= 0) return 'script';
        if (this.CODE_EXTS.indexOf(ext) >= 0) return 'code';
        if (!ext || this.TEXT_EXTS.indexOf(ext) >= 0) return 'text';
        return 'other';
    },

    /** 文件名 → lucide 图标名（列表行与抽屉标题共用这一份，别在组件里各写一套） */
    iconOf(name) {
        return this.FILE_ICONS[this.fileCategoryOf(name)] || 'file';
    },

    /** 文件名 → 类型色；纯文本 / 未知返回空串，交给列表本来的默认灰 */
    toneOf(name) {
        return this.FILE_TONES[this.fileCategoryOf(name)] || '';
    }
};

const SfViewer = {
    name: 'SfViewer',

    template: `
    <aside v-if="file" class="sf-drawer sf-drawer--viewer">
        <div class="sf-head">
            <div class="sf-title">
                <i :data-lucide="headerIcon" :style="headerTone"></i>
                <span class="sf-title-text">{{ file.name }}</span>
            </div>
            <button v-if="canPreview" class="sf-btn"
                    :title="previewMode ? '切回编辑' : '预览 Markdown'"
                    @click="togglePreview">
                <i :data-lucide="previewMode ? 'pencil-line' : 'eye'"></i>
            </button>
            <button class="sf-btn" title="下载" @click="download">
                <i data-lucide="download"></i>
            </button>
            <button class="sf-btn" title="关闭" @click="$emit('close')">
                <i data-lucide="x"></i>
            </button>
        </div>
        <div class="sf-viewer-body">
            <!-- 文本：编辑态。切预览时只藏不销毁，切回来光标与未保存的内容都还在 -->
            <div v-show="viewKind === 'text' && !previewMode" ref="editorHost" class="sf-editor-host"></div>
            <!-- 文本：Markdown 只读预览 -->
            <div v-if="viewKind === 'text' && previewMode" class="sf-preview markdown-body" v-html="previewHtml"></div>
            <!-- Word（.docx）：mammoth 转出的只读正文，容器与排版复用 Markdown 预览那一套 -->
            <div v-if="viewKind === 'docx'" class="sf-preview markdown-body sf-docx" v-html="docxHtml"></div>
            <!-- PDF：交给浏览器自带的阅读器，src 直接指 raw 接口 -->
            <div v-if="viewKind === 'pdf'" class="sf-pdf-wrap">
                <iframe :src="pdfUrl" title="PDF 预览"></iframe>
            </div>
            <div v-if="viewKind === 'image'" class="sf-image-wrap">
                <img :src="imageUrl" :alt="file.name">
            </div>
            <div v-if="viewKind === 'video'" class="sf-video-wrap">
                <video :src="videoUrl" controls preload="metadata" playsinline></video>
            </div>
            <div v-if="viewKind === 'other'" class="sf-empty">{{ viewerHint }}</div>
        </div>
        <div class="sf-status">
            <template v-if="viewKind === 'text'">
                <span class="sf-status-dot" :class="statusClass"></span>
                <span>{{ statusText }}</span>
            </template>
            <span class="sf-status-path" :title="file.path">{{ file.path }}</span>
            <span v-if="viewKind === 'text' && dirty" class="sf-status-action" @click="saveNow">立即保存</span>
        </div>
    </aside>
    `,

    props: {
        /** 要看的文件 { name, path }；null 即收起抽屉 */
        file: { type: Object, default: null },
        /** 当前模式的文件接口适配层（父组件的 fileApi() 结果） */
        api: { type: Object, required: true }
    },

    emits: ['close'],

    data() {
        return {
            viewKind: '',          // text | image | video | docx | pdf | other
            imageUrl: '',
            videoUrl: '',
            viewerHint: '',

            /* Word（.docx）预览：mammoth 把 OOXML 转出来的 HTML 字符串（只读，解析中为空） */
            docxHtml: '',

            /* PDF 预览：raw 接口地址，浏览器自带阅读器按它渲染 */
            pdfUrl: '',

            /* 编辑器状态 */
            editor: null,
            dirty: false,
            saving: false,
            saveFailed: false,
            savedTip: '',

            /* Markdown 预览：只读，内容取自编辑器里的当前值 */
            previewMode: false,
            previewHtml: ''
        };
    },

    computed: {
        /** 只有 Markdown 才出「编辑 / 预览」按钮 */
        canPreview() {
            return !!this.file && this.viewKind === 'text' && SfFileKind.isMarkdown(this.file.name);
        },

        headerIcon() {
            return SfFileKind.iconOf(this.file && this.file.name);
        },

        /**
         * 标题图标跟列表行同色，一左一右能对上。
         * <p>纯文本 / 未知类型兜 inherit，而不是返回 null —— lucide-safe 会把样式搬到 svg
         * 上存成基线，返回 null 时旧颜色会赖在基线上，从 .md 改名成 .txt 就洗不掉了。
         */
        headerTone() {
            return { color: SfFileKind.toneOf(this.file && this.file.name) || 'inherit' };
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
        /* 换文件 / 收起抽屉都走这一条：先把上一个没落盘的内容存了，再按新文件重开内容区 */
        file: {
            immediate: true,
            handler(newFile, oldFile) {
                this.onFileChange(newFile, oldFile);
            }
        }
    },

    mounted() {
        // mermaid 异步出图后重渲染，把预览里的源码占位换成 SVG（合并到同一帧）
        if (this.$md && typeof this.$md.onMermaidRendered === 'function') {
            this._offMermaid = this.$md.onMermaidRendered(() => {
                if (this.previewMode) this.renderPreview();
            });
        }
    },

    beforeUnmount() {
        if (this._offMermaid) {
            this._offMermaid();
            this._offMermaid = null;
        }
        clearTimeout(this._autoSaveTimer);
        this.destroyEditor();
    },

    updated() {
        this.scheduleIcons();
    },

    methods: {
        /* ==================== 打开文件 ==================== */

        async onFileChange(newFile, oldFile) {
            // 切文件 / 关抽屉前先把没落盘的存了，别让用户白写
            if (oldFile && this.dirty && this.editor) {
                await this.saveContent(this.editor.getValue(), oldFile.path);
            }
            clearTimeout(this._autoSaveTimer);
            this.destroyEditor();
            this.resetViewer();
            if (!newFile) return;

            const kind = SfFileKind.of(newFile.name);
            this.viewKind = kind;

            if (kind === 'image') {
                this.imageUrl = this.api.rawUrl(newFile.path);
                return;
            }
            if (kind === 'video') {
                this.videoUrl = this.api.rawUrl(newFile.path);
                return;
            }
            if (kind === 'pdf') {
                this.pdfUrl = this.api.rawUrl(newFile.path);
                return;
            }
            if (kind === 'text') {
                await this.loadText(newFile.path);
                return;
            }
            if (kind === 'docx') {
                await this.loadDocx(newFile.path);
                return;
            }
            this.viewerHint = '这个类型没法在线预览，点右上角下载吧';
        },

        async loadText(path) {
            try {
                const res = await this.api.read(path);
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

        /**
         * .docx → 取原始字节交给 mammoth 在浏览器端解析成 HTML。
         * <p>走 api.rawUrl 拿 ArrayBuffer，不能走 api.read —— 那个接口按文本读，二进制进去就废了。
         * 解析不出内容（加密文档 / 空文档 / 全图文档）时降级成「other」提示下载，不给页面上留空壳。
         */
        async loadDocx(path) {
            if (!window.mammoth) {
                this.viewKind = 'other';
                this.viewerHint = 'Word 预览组件没加载上，点右上角下载吧';
                return;
            }
            try {
                const res = await fetch(this.api.rawUrl(path));
                if (!res.ok) {
                    this.viewKind = 'other';
                    this.viewerHint = '读取失败：HTTP ' + res.status;
                    return;
                }
                const arrayBuffer = await res.arrayBuffer();
                const result = await window.mammoth.convertToHtml({ arrayBuffer });
                const html = (result && result.value) || '';
                if (!html.trim()) {
                    this.viewKind = 'other';
                    this.viewerHint = '没从这个文档里解析出内容，点右上角下载看原文件吧';
                    return;
                }
                this.docxHtml = html;
            } catch (e) {
                this.viewKind = 'other';
                this.viewerHint = '解析失败: ' + e.message;
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
            editor.session.setMode('ace/mode/' + SfFileKind.aceModeOf(path));
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

        /** 清空内容区状态（换文件 / 关抽屉都先走一遍） */
        resetViewer() {
            this.viewKind = '';
            this.imageUrl = '';
            this.videoUrl = '';
            this.viewerHint = '';
            this.docxHtml = '';
            this.pdfUrl = '';
            this.previewMode = false;
            this.previewHtml = '';
            this.dirty = false;
            this.saving = false;
            this.saveFailed = false;
            this.savedTip = '';
        },

        /* ==================== 保存 ==================== */

        async saveNow() {
            clearTimeout(this._autoSaveTimer);
            if (!this.file || !this.dirty || this.saving || !this.editor) return;
            await this.saveContent(this.editor.getValue(), this.file.path);
        },

        async saveContent(content, path) {
            if (!path) return;
            this.saving = true;
            try {
                const res = await this.api.write(path, content);
                if (res.status === 200) {
                    this.saveFailed = false;
                    this.savedTip = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                    // 保存期间用户又改了内容 → 保持脏标记，让下一轮自动保存接手
                    if (this.editor && this.file && this.file.path === path
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

        /* ==================== Markdown 预览 ==================== */

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

        /** 渲染的是编辑器里的当前内容：没保存也能看效果，状态条上的脏标记照旧亮着 */
        renderPreview() {
            const text = this.editor ? this.editor.getValue() : '';
            this.previewHtml = (this.$md && typeof this.$md.render === 'function')
                ? this.$md.render(text)
                : '';
        },

        /* ==================== 其他 ==================== */

        download() {
            if (!this.file) return;
            const a = document.createElement('a');
            a.href = this.api.rawUrl(this.file.path);
            a.download = this.file.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
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
    }
};
