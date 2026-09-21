/**
 * Shell 终端面板 —— 单层大抽屉
 *
 * 交互：
 *   导航轨「终端」按钮 → 从导航轨右侧铺满的大抽屉（深色终端风）
 *   输入命令回车执行；↑↓ 翻历史；Ctrl+C 中断运行中的命令
 *   顶部路径栏点击可改工作目录（用项目自绘 confirm-dialog 的输入形态）
 *
 * 为什么是异步的：
 *   命令不设超时（跑 npm install 也得能跑完），所以不能做成同步接口。
 *   exec 立即拿 jobId，然后按 offset 增量拉输出 —— 边跑边吐，随时能中断。
 *
 * Props:
 *   mainColor — String  主题色（目前只透传给内部 confirm-dialog）
 *
 * 公开方法（通过 ref 调用）：
 *   toggle() / open() / close()
 */
const ShellPanel = {
    name: 'ShellPanel',

    template: `
    <div v-if="visible" class="shell-overlay" @click.self="close">
        <aside class="shell-drawer" @keydown="onKeydown">
            <div class="sh-head">
                <div class="sh-title">
                    <i data-lucide="terminal"></i>
                    <span>终端</span>
                    <span class="sh-os">{{ osLabel }}</span>
                </div>
                <button class="sh-btn" title="清屏（只清显示，不影响运行中的命令）" @click="clearScreen">
                    <i data-lucide="eraser"></i>
                </button>
                <button class="sh-btn" title="关闭（任务会在后台继续跑）" @click="close">
                    <i data-lucide="x"></i>
                </button>
            </div>

            <div class="sh-cwd" :title="cwd" @click="editCwd">
                <i data-lucide="folder"></i>
                <span class="sh-cwd-text">{{ cwd || '（默认项目根目录）' }}</span>
            </div>

            <div class="sh-screen" ref="screen">
                <div v-if="blocks.length === 0" class="sh-hint">
                    在这里敲命令，回车执行。<br>
                    ↑↓ 翻历史 · Ctrl+C 中断运行中的命令 · 点上面的路径可以改工作目录
                </div>
                <div v-for="(block, i) in blocks"
                     :key="i"
                     class="sh-line"
                     :class="'is-' + block.kind">{{ block.text }}</div>
                <div v-if="running" class="sh-running">
                    <span class="sh-spinner"></span>运行中…（Ctrl+C 中断）
                </div>
            </div>

            <div class="sh-input-row">
                <span class="sh-prompt">&gt;</span>
                <input ref="input"
                       class="sh-input"
                       v-model="inputText"
                       :readonly="running"
                       :placeholder="running ? '命令执行中，Ctrl+C 可中断' : '输入命令，回车执行'"
                       @keydown.enter="submit"
                       @keydown.up.prevent="historyPrev"
                       @keydown.down.prevent="historyNext">
                <button v-if="running" class="sh-stop" @click="kill">
                    <i data-lucide="square"></i>
                    <span>停止</span>
                </button>
            </div>
        </aside>
    </div>

    <!-- 改工作目录用项目自绘的输入弹窗，和删除确认同一套视觉 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    data() {
        return {
            visible: false,

            /* 终端内容：命令回显 / 输出 / 系统提示 按顺序堆叠，改最后一个输出块实现「边跑边吐」 */
            blocks: [],

            /* 当前任务 */
            jobId: '',
            running: false,
            offset: 0,
            truncatedNoted: false,

            /* 环境 */
            cwd: '',
            osLabel: '',

            /* 输入与历史 */
            inputText: '',
            history: [],
            historyIndex: -1
        };
    },

    watch: {
        /* 开关状态上报父级：导航轨靠它把高亮对准真正打开的面板 */
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
            if (!this.cwd) {
                this.cwd = this.loadCwd();
            }
            if (!this.osLabel) {
                try {
                    const res = await API.shell.info();
                    if (res.status === 200) {
                        this.osLabel = res.data.os + ' · ' + res.data.shell;
                        if (!this.cwd) this.cwd = res.data.defaultCwd || '';
                    } else if (window.SbToast) {
                        window.SbToast.error(res.message || '拿不到 shell 环境信息');
                    }
                } catch (e) {
                    if (window.SbToast) window.SbToast.error('拿不到 shell 环境信息: ' + e.message);
                }
            }
            this.$nextTick(() => this.focusInput());
        },

        /** 关面板不等于杀任务：进程在后端继续跑，重新打开还能接着看输出 */
        close() {
            this.visible = false;
        },

        focusInput() {
            const el = this.$refs.input;
            if (el) el.focus();
        },

        clearScreen() {
            this.blocks = [];
        },

        /* ==================== 执行 ==================== */

        async submit() {
            const command = (this.inputText || '').trim();
            if (!command) return;
            if (this.running) {
                if (window.SbToast) window.SbToast.warning('还有命令在跑，先 Ctrl+C 中断它');
                return;
            }

            this.inputText = '';
            this.pushHistory(command);
            this.blocks.push({ kind: 'cmd', text: command });
            this.scrollToBottom();

            this.offset = 0;
            this.truncatedNoted = false;

            let res;
            try {
                res = await API.shell.exec(command, this.cwd);
            } catch (e) {
                this.blocks.push({ kind: 'note', text: '启动失败：' + e.message });
                this.scrollToBottom();
                return;
            }
            if (res.status !== 200) {
                this.blocks.push({ kind: 'note', text: '启动失败：' + (res.message || '未知错误') });
                this.scrollToBottom();
                return;
            }

            this.jobId = res.data.jobId;
            // 用后端解析后的真实工作目录回显，避免前端显示的和实际执行的不一致
            if (res.data.cwd) {
                this.cwd = res.data.cwd;
                this.saveCwd(this.cwd);
            }
            this.running = true;
            this.scrollToBottom();
            this.poll();
        },

        /** 增量拉输出：跑到结束为止，每次只取 offset 之后的新内容 */
        async poll() {
            clearTimeout(this._pollTimer);
            if (!this.jobId) return;
            let res;
            try {
                res = await API.shell.output(this.jobId, this.offset);
            } catch (e) {
                // 网络抖一下不代表命令死了，降频重试
                this._pollTimer = setTimeout(() => this.poll(), 800);
                return;
            }
            if (res.status !== 200) {
                this.running = false;
                this.blocks.push({ kind: 'note', text: '读取输出失败：' + (res.message || '任务可能已过期') });
                this.scrollToBottom();
                return;
            }

            const data = res.data || {};
            this.offset = data.offset || 0;

            if (data.chunk) {
                this.appendOutput(data.chunk);
                this.scrollToBottom();
            }
            if (data.truncated && !this.truncatedNoted) {
                this.truncatedNoted = true;
                this.blocks.push({ kind: 'note', text: '⚠ 输出超过上限，超出的部分已被丢弃' });
            }

            if (data.running) {
                this._pollTimer = setTimeout(() => this.poll(), 200);
            } else {
                this.running = false;
                const seconds = data.startedAt && data.finishedAt
                    ? ((data.finishedAt - data.startedAt) / 1000).toFixed(2)
                    : null;
                const parts = ['退出码 ' + data.exitCode];
                if (seconds != null) parts.push('耗时 ' + seconds + 's');
                this.blocks.push({ kind: 'note', text: '── ' + parts.join(' · ') });
                this.scrollToBottom();
            }
        },

        appendOutput(chunk) {
            const last = this.blocks[this.blocks.length - 1];
            if (last && last.kind === 'out') {
                last.text += chunk;
            } else {
                this.blocks.push({ kind: 'out', text: chunk });
            }
        },

        async kill() {
            if (!this.running || !this.jobId) return;
            try {
                const res = await API.shell.kill(this.jobId);
                this.blocks.push({
                    kind: 'note',
                    text: res.status === 200 ? '已发送中断' : ('中断失败：' + (res.message || ''))
                });
            } catch (e) {
                this.blocks.push({ kind: 'note', text: '中断失败：' + e.message });
            }
            this.scrollToBottom();
        },

        /* ==================== 键盘 ==================== */

        onKeydown(event) {
            // 有命令在跑时，Ctrl+C 变成「中断」；没跑的时候放行，不碍着复制
            const isCopy = (event.ctrlKey || event.metaKey)
                && (event.key === 'c' || event.key === 'C');
            if (isCopy && this.running) {
                event.preventDefault();
                event.stopPropagation();
                this.kill();
                return;
            }
            if (event.key === 'Escape') {
                this.close();
            }
        },

        /* ==================== 历史（localStorage） ==================== */

        loadHistory() {
            try {
                const raw = localStorage.getItem(HISTORY_KEY);
                this.history = raw ? JSON.parse(raw) : [];
                if (!Array.isArray(this.history)) this.history = [];
            } catch (e) {
                this.history = [];
            }
        },

        pushHistory(command) {
            if (this.history[this.history.length - 1] !== command) {
                this.history.push(command);
                if (this.history.length > MAX_HISTORY) this.history.shift();
                try {
                    localStorage.setItem(HISTORY_KEY, JSON.stringify(this.history));
                } catch (e) {
                    /* 隐私模式写不进去，忽略 */
                }
            }
            this.historyIndex = -1;
        },

        historyPrev() {
            if (!this.history.length) return;
            if (this.historyIndex === -1) {
                this.historyIndex = this.history.length;
            }
            this.historyIndex = Math.max(0, this.historyIndex - 1);
            this.inputText = this.history[this.historyIndex] || '';
        },

        historyNext() {
            if (this.historyIndex === -1) return;
            this.historyIndex += 1;
            if (this.historyIndex >= this.history.length) {
                this.historyIndex = -1;
                this.inputText = '';
            } else {
                this.inputText = this.history[this.historyIndex];
            }
        },

        /* ==================== 工作目录 ==================== */

        loadCwd() {
            try {
                return localStorage.getItem(CWD_KEY) || '';
            } catch (e) {
                return '';
            }
        },

        saveCwd(value) {
            try {
                localStorage.setItem(CWD_KEY, value);
            } catch (e) {
                /* 写不进去就算了，下次打开回落默认目录 */
            }
        },

        async editCwd() {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            let value;
            try {
                value = await dialog.show({
                    title: '工作目录',
                    message: '命令将在这个目录下执行（目录不存在时执行会报错）',
                    confirmText: '确定',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: this.cwd || '',
                    inputPlaceholder: '例如 E:\\local\\sunnybear-assistant'
                });
            } catch (e) {
                return;   // 取消
            }
            value = String(value || '').trim();
            if (!value || value === this.cwd) return;
            this.cwd = value;
            this.saveCwd(value);
            this.$nextTick(() => this.focusInput());
        },

        /* ==================== 展示辅助 ==================== */

        scrollToBottom() {
            this.$nextTick(() => {
                const el = this.$refs.screen;
                if (el) el.scrollTop = el.scrollHeight;
            });
        },

        /** lucide 只处理还没替换过的 [data-lucide]，用 rAF 合并频繁刷新 */
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
        this.loadHistory();
    },

    beforeUnmount() {
        clearTimeout(this._pollTimer);
    },

    updated() {
        this.scheduleIcons();
    }
};

/* ==================== 常量 ==================== */

const HISTORY_KEY = 'assistant-shell-history';
const CWD_KEY = 'assistant-shell-cwd';
const MAX_HISTORY = 100;
