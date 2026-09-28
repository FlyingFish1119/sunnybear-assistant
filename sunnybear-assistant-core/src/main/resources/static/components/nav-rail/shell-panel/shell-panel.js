/**
 * 终端面板 —— 真正的 Web 终端（xterm.js + WebSocket + 本地 PTY）
 *
 * 定位：一个能跑交互式程序的完整终端，不再是「一次一条命令」的执行面板。
 * 后端用 pty4j 拉起带伪终端的本地 shell（Windows ConPTY/PowerShell，Unix 登录 shell），
 * 前端用 xterm.js 渲染；vim / top / REPL 这类需要 tty 的程序现在都能正常用。
 *
 * 连接生命周期：
 *   打开面板 → 建立 WS → 后端为这条连接拉起一个 PTY；
 *   关闭面板 / 重开 → 断开 WS → 后端回收整个进程树。
 *   也就是说：面板关掉后 shell 不会保留，下次打开是一个全新会话。
 *
 * 协议（见 TerminalWebSocketHandler）：
 *   二进制帧 = 终端原始字节（收发都是）；文本帧 = JSON 控制消息（resize / exit / error）。
 *
 * 安全：后端握手只接受本机来源，非本机打开会连接失败。
 *
 * 交互：
 *   导航轨「终端」按钮 → 从导航轨右侧铺满的大抽屉
 *   全屏终端，直接敲键即可；右上角有清屏 / 重开会话 / 关闭
 *
 * Props:
 *   mainColor — String  主题色（终端光标 / 选中底色与面板强调色取它）
 *
 * 公开方法（通过 ref 调用）：
 *   toggle() / open() / close()
 */
/**
 * 把任意 CSS 颜色转成 rgba(...)，供 xterm 主题里需要透明度的项（如选中底色）使用。
 * mainColor 可能是色名 / hex / rgb()，统一交给浏览器解析后再取通道值。
 */
function shellRgba(color, alpha) {
    if (!color) return 'rgba(125, 211, 160, ' + alpha + ')';
    try {
        const div = document.createElement('div');
        div.style.color = color;
        div.style.display = 'none';
        document.body.appendChild(div);
        const computed = getComputedStyle(div).color;
        document.body.removeChild(div);
        const m = computed.match(/[\d.]+/g);
        if (!m || m.length < 3) return color;
        return 'rgba(' + m[0] + ', ' + m[1] + ', ' + m[2] + ', ' + alpha + ')';
    } catch (e) {
        return color;
    }
}

const ShellPanel = {
    name: 'ShellPanel',

    template: `
    <div v-if="visible" class="shell-overlay" :style="{ '--sh-accent': mainColor || '#7dd3a0' }" @click.self="close">
        <aside class="shell-drawer">
            <div class="sh-head">
                <div class="sh-title">
                    <i data-lucide="terminal"></i>
                    <span>终端</span>
                    <span class="sh-status" :class="'is-' + status">{{ statusLabel }}</span>
                </div>
                <button class="sh-btn" title="清屏（只清显示，不影响运行中的程序）" @click="clearScreen">
                    <i data-lucide="eraser"></i>
                </button>
                <button class="sh-btn" title="重开会话（关闭当前 shell，重新开一个）" @click="restart">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="sh-btn" title="关闭（结束当前终端会话）" @click="close">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="sh-term" ref="termHost"></div>
            <div v-if="status !== 'connected'" class="sh-veil">
                <span v-if="status === 'connecting'" class="sh-spinner"></span>
                <i v-else-if="status === 'error'" data-lucide="triangle-alert" class="sh-veil-icon"></i>
                <span class="sh-veil-text">{{ statusText }}</span>
            </div>
            <div class="sh-ai-row">
                <i data-lucide="sparkles" class="sh-ai-icon"></i>
                <input ref="aiInput"
                       class="sh-ai-input"
                       v-model="aiText"
                       :disabled="aiBusy || status !== 'connected'"
                       :placeholder="aiPlaceholder"
                       @keydown.enter.prevent="submitAi">
            </div>
        </aside>
    </div>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    data() {
        return {
            visible: false,
            status: 'idle',          // idle | connecting | connected | closed | error
            statusMessage: '',
            aiText: '',              // 底部 AI / 快捷命令输入框
            aiBusy: false            // 正在向后端请求生成命令
        };
    },

    computed: {
        statusLabel() {
            return {
                idle: '未连接',
                connecting: '连接中',
                connected: '已连接',
                closed: '已断开',
                error: '连接失败'
            }[this.status] || '';
        },
        statusText() {
            switch (this.status) {
                case 'connecting': return '正在建立终端会话…';
                case 'error': return this.statusMessage || '连接失败（终端仅允许本机访问）';
                case 'closed': return '会话已结束，点右上角 ↻ 重开';
                default: return '未连接';
            }
        },
        aiPlaceholder() {
            if (this.aiBusy) return '正在生成命令…';
            if (this.status !== 'connected') return '终端未连接';
            return '描述你想做什么，自动生成命令填入终端（如：拉取git最新提交）';
        }
    },

    watch: {
        visible(val) {
            this.$emit('visible-change', val);
            if (val) this.$nextTick(() => this.onShow());
        },
        /* 主题色变化时热更新终端主题（光标 / 选中色跟随主色），不必重开会话 */
        mainColor() {
            if (this.term) {
                this.term.options.theme = this.buildTheme();
            }
        }
    },

    methods: {
        toggle() {
            if (this.visible) this.close(); else this.open();
        },

        open() {
            this.visible = true;
        },

        close() {
            this.teardown();
            this.visible = false;
        },

        /* ==================== 生命周期 ==================== */

        onShow() {
            if (!this.initTerminal()) return;
            this.connect();
        },

        /** 创建 xterm 实例并挂到 DOM；返回是否成功 */
        initTerminal() {
            if (typeof Terminal === 'undefined') {
                this.status = 'error';
                this.statusMessage = 'xterm.js 未加载，终端不可用';
                return false;
            }
            const host = this.$refs.termHost;
            if (!host) return false;

            this.term = new Terminal({
                cursorBlink: true,
                fontFamily: 'Consolas, "Cascadia Mono", "JetBrains Mono", "Courier New", monospace',
                fontSize: 14,
                lineHeight: 1.2,
                scrollback: 8000,
                allowProposedApi: true,
                theme: this.buildTheme()
            });
            this.fitAddon = new FitAddon.FitAddon();
            this.term.loadAddon(this.fitAddon);
            this.term.open(host);

            // 用户输入 → 二进制帧
            this.term.onData(data => this.send(this.encode(data)));
            // 非 UTF-8 输入（鼠标上报等）→ 逐字节发
            this.term.onBinary(data => {
                const bytes = new Uint8Array(data.length);
                for (let i = 0; i < data.length; i++) bytes[i] = data.charCodeAt(i) & 0xff;
                this.send(bytes);
            });
            // 终端尺寸变化 → 通知后端 PTY
            this.term.onResize(({ cols, rows }) => this.sendResize(cols, rows));

            return true;
        },

        disposeTerminal() {
            if (this.term) {
                try { this.term.dispose(); } catch (e) { /* 已销毁 */ }
                this.term = null;
            }
            this.fitAddon = null;
        },

        teardown() {
            this.disconnect();
            this.disposeTerminal();
            window.removeEventListener('resize', this.onWindowResize);
            this.aiText = '';
            this.aiBusy = false;
        },

        /* ==================== 连接 ==================== */

        connect() {
            if (!this.term) return;
            this.status = 'connecting';
            this.statusMessage = '';
            this._manualClose = false;

            // 先按当前 DOM 尺寸 fit 一次，拿到尽可能准的行列数，再据此建连
            this.$nextTick(() => {
                this.fit();
                let ws;
                try {
                    ws = new WebSocket(API.ws.terminalUrl(this.term.cols, this.term.rows));
                } catch (e) {
                    this.fail('无法建立连接：' + e.message);
                    return;
                }
                ws.binaryType = 'arraybuffer';
                this.ws = ws;

                ws.onopen = () => {
                    this.status = 'connected';
                    window.addEventListener('resize', this.onWindowResize);
                    this.$nextTick(() => {
                        this.fit();
                        if (this.term) this.term.focus();
                    });
                };
                ws.onmessage = ev => this.onMessage(ev);
                ws.onerror = () => {
                    if (!this._manualClose) this.fail('连接失败（终端仅允许本机访问）');
                };
                ws.onclose = () => {
                    if (this.ws === ws) this.ws = null;
                    if (!this._manualClose && this.status !== 'error') {
                        this.status = 'closed';
                    }
                };
            });
        },

        disconnect() {
            this._manualClose = true;
            if (this.ws) {
                try { this.ws.close(); } catch (e) { /* 忽略 */ }
                this.ws = null;
            }
        },

        onMessage(ev) {
            if (typeof ev.data === 'string') {
                this.onControl(ev.data);
            } else if (ev.data && this.term) {
                this.term.write(new Uint8Array(ev.data));
            }
        },

        onControl(text) {
            let msg;
            try { msg = JSON.parse(text); } catch (e) { return; }
            if (msg.type === 'exit') {
                const code = (msg.code == null || msg.code < 0) ? '—' : msg.code;
                this.writeNote('\r\n[会话已结束，退出码 ' + code + ']');
                this.status = 'closed';
            } else if (msg.type === 'error') {
                this.fail(msg.message || '终端发生错误');
            }
        },

        fail(message) {
            this.status = 'error';
            this.statusMessage = message;
            this.writeNote('\r\n[错误] ' + message);
        },

        /* ==================== 数据发送 ==================== */

        send(bytes) {
            if (this.ws && this.ws.readyState === WebSocket.OPEN && bytes && bytes.length) {
                this.ws.send(bytes);
            }
        },

        sendResize(cols, rows) {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({ type: 'resize', cols: cols, rows: rows }));
            }
        },

        encode(str) {
            if (window.TextEncoder) return new TextEncoder().encode(str);
            const bytes = new Uint8Array(str.length);
            for (let i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
            return bytes;
        },

        writeNote(text) {
            if (this.term) this.term.write('\x1b[90m' + text + '\x1b[0m');
        },

        /* ==================== 底部 AI 输入框 ==================== */

        /**
         * 底部输入框只做一件事：把自然语言需求交给后端，生成一条命令后「敲」进终端但不回车，
         * 由用户在命令行确认后再执行（避免 AI 生成的命令在本机直接跑起来）。
         * 想直接执行命令，请在终端里敲 —— 那才是终端的本职。
         */
        async submitAi() {
            const prompt = (this.aiText || '').trim();
            if (!prompt || this.aiBusy) return;
            if (this.status !== 'connected') {
                if (window.SbToast) window.SbToast.warning('终端未连接');
                return;
            }

            this.aiBusy = true;
            try {
                const res = await API.terminal.assist(prompt);
                if (res.status !== 200 || !res.data || !res.data.command) {
                    const msg = (res && res.message) || '没能生成命令';
                    if (window.SbToast) window.SbToast.error(msg);
                    else this.writeNote('\r\n[AI] ' + msg);
                    return;
                }
                this.aiText = '';
                // 只填入命令行、不回车：留给用户确认后再执行
                this.send(this.encode(res.data.command));
                this.focusTerminal();
            } catch (e) {
                const msg = '生成命令失败：' + e.message;
                if (window.SbToast) window.SbToast.error(msg);
                else this.writeNote('\r\n[AI] ' + msg);
            } finally {
                this.aiBusy = false;
            }
        },

        focusTerminal() {
            this.$nextTick(() => {
                if (this.term) this.term.focus();
            });
        },

        /* ==================== 交互 ==================== */

        clearScreen() {
            if (this.term) {
                this.term.clear();
                this.term.focus();
            }
        },

        restart() {
            this.teardown();
            this.$nextTick(() => {
                if (!this.initTerminal()) return;
                this.status = 'idle';
                this.connect();
            });
        },

        fit() {
            if (!this.fitAddon || !this.term) return;
            try { this.fitAddon.fit(); } catch (e) { /* DOM 尺寸为 0 时忽略 */ }
        },

        /**
         * xterm 主题：底/前景固定为面板深色，光标与选中底色跟随应用主色（mainColor）。
         * ANSI 十六色保持标准值，避免把 git diff 等语义色改乱。
         */
        buildTheme() {
            const accent = this.mainColor || '#7dd3a0';
            return {
                background: '#1b1b1f',
                foreground: '#d4d4d8',
                cursor: accent,
                cursorAccent: '#1b1b1f',
                selectionBackground: shellRgba(accent, 0.28),
                black: '#1b1b1f', red: '#f27a7d', green: '#7dd3a0',
                yellow: '#d8b96a', blue: '#7aa2f7', magenta: '#bb9af7',
                cyan: '#7dcfff', white: '#d4d4d8',
                brightBlack: '#6b7280', brightRed: '#ff9a9c', brightGreen: '#9ee7bb',
                brightYellow: '#ecd28a', brightBlue: '#9ab8ff', brightMagenta: '#d0b3ff',
                brightCyan: '#9fe0ff', brightWhite: '#ffffff'
            };
        },

        onWindowResize() {
            clearTimeout(this._resizeTimer);
            this._resizeTimer = setTimeout(() => this.fit(), 100);
        },

        /* ==================== 图标 ==================== */

        scheduleIcons() {
            if (this._iconScheduled) return;
            this._iconScheduled = true;
            requestAnimationFrame(() => {
                this._iconScheduled = false;
                if (window.lucide) window.lucide.createIcons();
            });
        }
    },

    beforeUnmount() {
        clearTimeout(this._resizeTimer);
        this.teardown();
    },

    updated() {
        this.scheduleIcons();
    }
};
