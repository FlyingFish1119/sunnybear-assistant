/**
 * MCP 面板 —— 双层抽屉（Server 列表 + 连接编辑器）
 *
 * 与会话文件 / 记忆 / 知识库面板同一套门户：导航轨「MCP」按钮 → 第一层抽屉
 * （Server 列表，贴左侧滑出）→ 点某个 Server 或「+」→ 第二层抽屉（编辑区，
 * 接着往右展开）。不再用弹窗。
 *
 * 编辑区按 transport 切换字段：
 *   · http  —— Streamable HTTP 远端服务（url）
 *   · stdio —— 本地子进程（command / args / cwd / env）
 *
 * 保存全凭手动：点保存或 Ctrl/Cmd+S 落库，打字期间不自动保存；切 Server /
 * 收起编辑区 / 关面板时若仍有未保存内容会先落一次，防止白写。
 * 删除走项目自绘 confirm-dialog（danger 形态），Esc 依次往回关（先收编辑）。
 *
 * 保存走 settings/mcp/save：后端落盘 settings/mcp_settings.json 并热重建连接，
 * 无需重启。注意：是否注册 MCP 工具集由 application.yml 的
 * engine.tool.mcp.enable 控制（需重启），本面板只管理「连接明细」。
 *
 * Props:
 *   mainColor — String  主色（图标与选中底色取它）
 *
 * 公开方法（与 memory-panel / session-files / shell-panel 同一套约定）：
 *   toggle()   — 开 / 关整个面板
 *   open()     — 打开并加载
 *   closeAll() — 关闭面板（含第二层）
 *   close()    — 对外统一叫 close，导航轨切换时统一调这个
 *
 * Emits:
 *   visible-change(visible) — 供导航轨对齐高亮
 */
const McpPanel = {
    name: 'McpPanel',

    template: `
    <div v-if="visible" class="mcp-overlay" @click.self="closeAll">
        <!-- 第一层：Server 列表 -->
        <aside class="mcp-drawer mcp-drawer--list">
            <div class="mcp-head">
                <div class="mcp-title">
                    <i data-lucide="plug"></i>
                    <span class="mcp-title-text">MCP Server</span>
                </div>
                <button class="mcp-btn" title="新增 Server" @click="openEditor(null)">
                    <i data-lucide="plus"></i>
                </button>
                <button class="mcp-btn" title="刷新" :disabled="loading" @click="refresh">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="mcp-btn" title="关闭" @click="closeAll">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="mcp-list">
                <div v-if="loading" class="mcp-hint">加载中…</div>
                <div v-else-if="clients.length === 0" class="mcp-empty">
                    还没有配置 MCP Server<br>点右上角「+」添加一个
                </div>
                <template v-else>
                    <div v-for="(c, idx) in clients"
                         :key="rowKey(c, idx)"
                         class="mcp-row"
                         :class="{ 'is-active': !isNew && currentKey === c.serverName }"
                         @click="openEditor(idx)">
                        <span class="mcp-row-icon">
                            <i :data-lucide="c.transport === 'stdio' ? 'terminal' : 'globe'"></i>
                        </span>
                        <span class="mcp-row-body">
                            <span class="mcp-row-name">{{ c.serverName }}</span>
                            <span class="mcp-row-sub">{{ describe(c) }}</span>
                        </span>
                        <span class="mcp-row-actions" @click.stop>
                            <button class="mcp-row-btn is-danger" title="删除" @click.stop="removeClient(idx)">
                                <i data-lucide="trash-2"></i>
                            </button>
                        </span>
                    </div>
                </template>
            </div>
        </aside>

        <!-- 第二层：连接编辑器 -->
        <aside v-if="currentKey" class="mcp-drawer mcp-drawer--editor">
            <div class="mcp-head">
                <div class="mcp-title">
                    <i :data-lucide="form.transport === 'stdio' ? 'terminal' : 'globe'"></i>
                    <span class="mcp-title-text">{{ form.serverName || (isNew ? '新增 MCP Server' : 'MCP Server') }}</span>
                </div>
                <button v-if="!isNew" class="mcp-btn" title="删除" @click="removeCurrent">
                    <i data-lucide="trash-2"></i>
                </button>
                <button class="mcp-btn" title="保存（Ctrl+S）" :disabled="!dirty || saving" @click="saveNow">
                    <i data-lucide="save"></i>
                </button>
                <button class="mcp-btn" title="关闭" @click="closeViewer">
                    <i data-lucide="x"></i>
                </button>
            </div>

            <div class="mcp-form">
                <label class="mcp-field">
                    <span class="mcp-field-label">名称</span>
                    <input class="mcp-input" v-model="form.serverName"
                           placeholder="调用时用 serverName 指定，例如 Blender Mcp"
                           @input="markDirty">
                </label>

                <label class="mcp-field">
                    <span class="mcp-field-label">传输方式</span>
                    <el-select v-model="form.transport" style="width:100%" @change="markDirty">
                        <el-option label="http — Streamable HTTP 远端服务" value="http"></el-option>
                        <el-option label="stdio — 本地子进程" value="stdio"></el-option>
                    </el-select>
                </label>

                <template v-if="form.transport === 'http'">
                    <label class="mcp-field">
                        <span class="mcp-field-label">URL</span>
                        <input class="mcp-input" v-model="form.url"
                               placeholder="https://example.com/mcp" @input="markDirty">
                    </label>
                </template>
                <template v-else>
                    <label class="mcp-field">
                        <span class="mcp-field-label">Command</span>
                        <input class="mcp-input" v-model="form.command"
                               placeholder="例如 uvx / npx" @input="markDirty">
                    </label>
                    <label class="mcp-field">
                        <span class="mcp-field-label">Args</span>
                        <textarea class="mcp-textarea" v-model="form.argsText" rows="3"
                                  placeholder="每行一个参数，例如：&#10;blender-mcp" @input="markDirty"></textarea>
                    </label>
                    <label class="mcp-field">
                        <span class="mcp-field-label">工作目录</span>
                        <input class="mcp-input" v-model="form.cwd"
                               placeholder="留空继承当前进程（可选）" @input="markDirty">
                    </label>
                    <label class="mcp-field">
                        <span class="mcp-field-label">环境变量</span>
                        <textarea class="mcp-textarea" v-model="form.envText" rows="3"
                                  placeholder="每行一个 KEY=VALUE（可选）" @input="markDirty"></textarea>
                    </label>
                </template>

                <label class="mcp-field">
                    <span class="mcp-field-label">超时(秒)</span>
                    <el-input-number v-model="form.timeoutS" :min="1" :max="600"
                                     controls-position="right" @change="markDirty"></el-input-number>
                </label>
            </div>

            <div class="mcp-status">
                <span class="mcp-status-dot" :class="statusClass"></span>
                <span>{{ statusText }}</span>
                <span class="mcp-status-action" v-if="dirty && !saving" @click="saveNow">立即保存</span>
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
            loading: false,
            clients: [],

            /* 当前编辑位：'' = 收起；'__draft__' = 新 Server 草稿；否则为原 serverName */
            currentKey: '',
            isNew: false,
            form: {
                serverName: '',
                transport: 'http',
                url: '',
                command: '',
                argsText: '',
                cwd: '',
                envText: '',
                timeoutS: 20
            },

            dirty: false,
            saving: false,
            saveFailed: false,
            savedTip: ''
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
            return this.savedTip ? '已保存 ' + this.savedTip : '已保存并生效';
        }
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
                this.closeAll();
            } else {
                this.open();
            }
        },

        async open() {
            this.visible = true;
            await this.refresh();
        },

        closeAll() {
            this.closeViewer();
            this.visible = false;
        },

        /** 对外统一叫 close，和 shell-panel 对齐（导航轨切换时统一调这个） */
        close() {
            this.closeAll();
        },

        /** 收起编辑区（有未保存先落盘），不动第一层 */
        async closeViewer() {
            if (this.dirty) await this.saveNow();
            this.currentKey = '';
            this.isNew = false;
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
        },

        /* ==================== 数据 ==================== */

        async refresh() {
            this.loading = true;
            try {
                const res = await API.settings.mcp.get();
                if (res.status === 200) {
                    this.clients = (res.data && res.data.clients) ? res.data.clients : [];
                    // 正在编辑的 Server 若已被删（别处删了），收起编辑区
                    if (!this.isNew && this.currentKey
                        && !this.clients.some(c => c.serverName === this.currentKey)) {
                        this.currentKey = '';
                        this.dirty = false;
                    }
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '拉取 MCP 配置失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('拉取 MCP 配置失败: ' + e.message);
            } finally {
                this.loading = false;
            }
        },

        rowKey(c, idx) {
            return (c.serverName || 'server') + '#' + idx;
        },

        describe(c) {
            if ((c.transport || 'http') === 'stdio') {
                const parts = [c.command || ''].concat(c.args || []).filter(Boolean);
                return parts.join(' ') || '（未配置命令）';
            }
            return c.url || '（未配置 URL）';
        },

        blankForm() {
            return {
                serverName: '',
                transport: 'http',
                url: '',
                command: '',
                argsText: '',
                cwd: '',
                envText: '',
                timeoutS: 20
            };
        },

        /* ==================== 编辑 ==================== */

        async openEditor(idx) {
            if (idx != null && !this.isNew && this.currentKey === this.clients[idx].serverName) {
                return;   // 点的是当前正在编辑的，不重开
            }
            // 切编辑位前把没保存的先落盘，别让上一个的输入白写
            if (this.currentKey && this.dirty) {
                await this.saveNow();
            }
            if (idx == null) {
                this.isNew = true;
                this.currentKey = '__draft__';
                this.form = this.blankForm();
            } else {
                const c = this.clients[idx] || {};
                this.isNew = false;
                this.currentKey = c.serverName;
                this.form = this.loadForm(c);
            }
            this.dirty = false;
            this.saveFailed = false;
            this.savedTip = '';
            this.scheduleIcons();
        },

        loadForm(c) {
            return {
                serverName: c.serverName || '',
                transport: c.transport || 'http',
                url: c.url || '',
                command: c.command || '',
                argsText: (c.args || []).join('\n'),
                cwd: c.cwd || '',
                envText: Object.entries(c.env || {})
                    .map(kv => kv[0] + '=' + (kv[1] == null ? '' : kv[1])).join('\n'),
                timeoutS: c.timeoutS || 20
            };
        },

        markDirty() {
            this.dirty = true;
            this.saveFailed = false;
        },

        buildClient() {
            const transport = this.form.transport === 'stdio' ? 'stdio' : 'http';
            return {
                serverName: (this.form.serverName || '').trim(),
                transport: transport,
                url: transport === 'http' ? (this.form.url || '').trim() : null,
                command: transport === 'stdio' ? (this.form.command || '').trim() : null,
                args: transport === 'stdio' ? this.parseLines(this.form.argsText) : [],
                cwd: transport === 'stdio' ? (this.form.cwd || '').trim() : null,
                env: transport === 'stdio' ? this.parseEnv(this.form.envText) : {},
                timeoutS: this.form.timeoutS || 20
            };
        },

        parseLines(text) {
            return String(text || '')
                .split(/\r?\n/)
                .map(s => s.trim())
                .filter(s => s.length > 0);
        },

        parseEnv(text) {
            const env = {};
            for (const line of String(text || '').split(/\r?\n/)) {
                const trimmed = line.trim();
                if (!trimmed) continue;
                const eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                const key = trimmed.substring(0, eq).trim();
                const value = trimmed.substring(eq + 1).trim();
                if (key) env[key] = value;
            }
            return env;
        },

        /* ==================== 保存 / 删除 ==================== */

        async saveNow() {
            if (!this.currentKey || !this.dirty || this.saving) return false;

            const client = this.buildClient();
            if (!client.serverName) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('请填写名称');
                return false;
            }
            if (client.transport === 'http' && !client.url) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('http 传输需要填写 URL');
                return false;
            }
            if (client.transport === 'stdio' && !client.command) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('stdio 传输需要填写 command');
                return false;
            }
            const duplicate = this.clients.some(c => c.serverName === client.serverName && c.serverName !== this.currentKey);
            if (duplicate) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.warning('名称已存在：' + client.serverName);
                return false;
            }

            const next = this.clients.slice();
            const existIdx = next.findIndex(c => c.serverName === this.currentKey);
            if (existIdx >= 0) {
                next[existIdx] = client;
            } else {
                next.push(client);
            }

            return await this.persist(next, client);
        },

        /** 统一落盘：POST 全量 clients；成功后刷新列表并把编辑位对准已保存项 */
        async persist(nextClients, savedClient) {
            this.saving = true;
            try {
                const res = await API.settings.mcp.save({ clients: nextClients });
                if (res.status === 200) {
                    this.saveFailed = false;
                    this.savedTip = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                    this.dirty = false;
                    this.isNew = false;
                    this.currentKey = savedClient && savedClient.serverName ? savedClient.serverName : this.currentKey;
                    if (window.SbToast) window.SbToast.success('已保存并生效');
                    await this.refresh();
                    return true;
                }
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.error(res.message || '保存失败');
            } catch (e) {
                this.saveFailed = true;
                if (window.SbToast) window.SbToast.error('保存失败: ' + e.message);
            } finally {
                this.saving = false;
            }
            return false;
        },

        async removeCurrent() {
            const idx = this.clients.findIndex(c => c.serverName === this.currentKey);
            if (idx < 0) return;
            await this.removeClient(idx);
        },

        async removeClient(idx) {
            const target = this.clients[idx];
            if (!target) return;
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            try {
                await dialog.show({
                    title: '删除 MCP Server',
                    message: '确定删除「' + target.serverName + '」吗？保存后会立即断开该连接。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return;   // 取消
            }
            const next = this.clients.slice();
            next.splice(idx, 1);
            const wasEditing = !this.isNew && this.currentKey === target.serverName;
            const ok = await this.persist(next, null);
            if (ok && wasEditing) {
                this.currentKey = '';
                this.isNew = false;
                this.dirty = false;
                this.saveFailed = false;
                this.savedTip = '';
            }
        },

        /* ==================== 辅助 ==================== */

        onKeydown(e) {
            if (!this.visible) return;
            if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
                if (this.currentKey) {
                    e.preventDefault();
                    this.saveNow();
                }
                return;
            }
            if (e.key !== 'Escape') return;
            if (this.currentKey) {
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
    },

    updated() {
        this.scheduleIcons();
    }
};
