/**
 * Agent Log 组件 — 顶栏开关 + 右侧浮窗（Agent Log / 后台任务 两个页签）
 *
 * 以「插件」形态存在：加载时自注册进顶栏右栏（见文件末尾，须在 message-topbar.js 之后）。
 * 未加载本文件的页面（如角色/世界页）即完全无此入口，无需 hideBuiltin 反向下线。
 *
 * 组件自包含：开关按钮渲染在顶栏槽内；面板经 Teleport 投放到
 * .message-area-wrapper 下（绝对定位于消息区右侧，不参与 flex 布局）。
 * 展开态由组件内部持有，按钮高亮与面板开合天然同步，不再走 WsBus 事件流转。
 *
 * 两个页签：
 *   - Agent Log：子 Agent 执行日志，订阅 'AGENT_LOG' 信号追加；
 *   - 后台任务：正在后台跑的命令 / 扩展脚本，按 1 秒轮询列表，展开后增量拉日志，
 *     可直接结束（杀整棵进程树）。任务一收尾就从列表消失，日志本体留在会话文件 logs/ 下。
 *
 * 数据订阅：
 *   - 'AGENT_LOG' 信号，追加当前会话的子 Agent 日志；
 *   - 本地事件 'agent-log:clear'（切会话/新建会话）清空日志与后台任务视图。
 *
 * 依赖注入（均可选，缺失时降级）：
 *   wsBus        — WebSocket 消息总线
 *   sessionStore — 会话仓库（用于按当前会话过滤日志与任务）
 *   appSettings  — 应用级设置（主题色）
 *
 * 公开方法（通过 ref 调用）：
 *   toggle() / open() / close() / clearLogs()
 */
const AgentLogSidebar = {
    name: 'AgentLogSidebar',

    template: `
    <button class="sidebar-toggle-btn" @click="toggle"
            :title="visible ? '折叠 Agent Log' : '展开 Agent Log'"
            :style="visible ? {color: mainColor} : {}">
        <i data-lucide="activity" style="width: 18px; height: 18px;"></i>
    </button>
    <teleport v-if="anchorReady" to=".message-area-wrapper">
        <div class="agent-log-sidebar" :class="{ collapsed: !visible }">
            <div class="agent-log-header" :style="{ borderBottomColor: mainColor }">
                <div class="agent-log-tabs">
                    <button class="agent-log-tab" :class="{ active: activeTab === 'agent' }"
                            :style="activeTab === 'agent' ? { color: mainColor } : {}"
                            @click="switchTab('agent')">
                        <i data-lucide="activity" style="width: 14px; height: 14px;"></i>
                        <span>Agent Log</span>
                        <span v-if="logs.length > 0" class="agent-log-badge">{{ logs.length }}</span>
                    </button>
                    <button class="agent-log-tab" :class="{ active: activeTab === 'task' }"
                            :style="activeTab === 'task' ? { color: mainColor } : {}"
                            @click="switchTab('task')">
                        <i data-lucide="terminal" style="width: 14px; height: 14px;"></i>
                        <span>后台任务</span>
                        <span v-if="tasks.length > 0" class="agent-log-badge">{{ tasks.length }}</span>
                    </button>
                </div>
                <button class="agent-log-close-btn" @click="toggle" title="关闭面板">
                    <i data-lucide="x" style="width: 16px; height: 16px;"></i>
                </button>
            </div>

            <!-- ==================== Agent Log 页 ==================== -->
            <div v-if="activeTab === 'agent'" class="agent-log-list" ref="logList">
                <div v-if="logs.length === 0" class="agent-log-empty">
                    <i data-lucide="scroll-text" style="width: 28px; height: 28px;"></i>
                    <span>暂无子 Agent 日志</span>
                    <span class="agent-log-empty-hint">执行 task_run 等子任务时将在此显示中间过程</span>
                </div>
                <div v-for="entry in logs" :key="entry.id" class="agent-log-entry"
                     :class="{ expanded: expandedId === entry.id }">
                    <div class="agent-log-entry-header" @click="toggleEntry(entry)">
                        <span class="agent-log-dot" :class="'dot-' + entry.phase"></span>
                        <span v-if="entry.iteration > 0" class="agent-log-iter">#{{ entry.iteration }}</span>
                        <span class="agent-log-title">{{ entry.title }}</span>
                        <span class="agent-log-time">{{ formatTime(entry.timestamp) }}</span>
                        <i v-if="entry.content" class="agent-log-expand-icon"
                           :class="{ rotated: expandedId === entry.id }"
                           data-lucide="chevron-down"
                           style="width: 12px; height: 12px;"></i>
                    </div>
                    <div v-if="entry.content && expandedId === entry.id" class="agent-log-entry-body">
                        <pre>{{ entry.content }}</pre>
                    </div>
                </div>
            </div>

            <!-- ==================== 后台任务页 ==================== -->
            <div v-else class="agent-log-list">
                <div v-if="tasks.length === 0" class="agent-log-empty">
                    <i data-lucide="terminal" style="width: 28px; height: 28px;"></i>
                    <span>暂无后台任务</span>
                    <span class="agent-log-empty-hint">
                        命令或扩展脚本转入后台运行时会在此出现，可看实时输出，也可以随时结束
                    </span>
                </div>
                <div v-for="task in tasks" :key="task.id" class="agent-task-entry"
                     :class="{ expanded: taskExpandedId === task.id }">
                    <div class="agent-task-header" @click="toggleTaskLog(task)">
                        <span class="agent-log-dot dot-running"></span>
                        <span class="agent-task-kind">{{ task.type === 'SCRIPT' ? '脚本' : '命令' }}</span>
                        <span class="agent-task-title" :title="task.title">{{ task.title }}</span>
                        <span class="agent-task-time">{{ formatDuration(task.startTime, taskNow) }}</span>
                        <button class="agent-task-kill" :class="{ confirming: confirmingId === task.id }"
                                :disabled="killingId === task.id"
                                @click.stop="killTask(task)">
                            {{ killingId === task.id ? '结束中' : (confirmingId === task.id ? '再点一次' : '结束') }}
                        </button>
                    </div>
                    <div v-if="taskExpandedId === task.id" class="agent-task-body">
                        <pre ref="taskLogView" @scroll="onTaskLogScroll">{{ currentTaskLog }}</pre>
                    </div>
                </div>
            </div>
        </div>
        <div class="agent-log-overlay" :class="{ visible: visible && isMobile }" @click="toggle"></div>
    </teleport>
    `,

    inject: {
        // 可选注入：插件页未提供 wsBus / sessionStore 时降级
        wsBus: { default: null },
        sessionStore: { default: null },
        appSettings: { default: null }
    },

    computed: {
        /** 主题色：从应用级设置注入 */
        mainColor: function () {
            return (this.appSettings && this.appSettings.mainColor) || 'lightsalmon';
        },

        /** 当前展开任务的日志文本；日志很长时只保留尾部，免得每次轮询都整段重渲染 */
        currentTaskLog: function () {
            var id = this.taskExpandedId;
            if (!id) return '';
            var cached = this.taskLogs[id];
            if (!cached || !cached.content) return '等待输出…';
            return cached.content;
        }
    },

    data: function () {
        return {
            logs: [],
            visible: false,
            expandedId: null,
            isMobile: window.innerWidth < 769,
            // Teleport 目标就绪标记：面板要投放到 .message-area-wrapper，
            // 初始 patch 时该元素可能尚未入文档，等 mounted（post-flush）再开启
            anchorReady: false,

            /* ---- 后台任务页 ---- */
            activeTab: 'agent',
            tasks: [],
            /** 任务 ID -> { content, offset }；任务结束后随手清掉，不留缓存 */
            taskLogs: {},
            taskExpandedId: null,
            /** 用于每秒刷新「运行时长」，模板里显式传进 formatDuration 才会跟着重算 */
            taskNow: Date.now(),
            /** 待二次确认结束的任务 ID（3 秒内不再点就取消） */
            confirmingId: null,
            /** 正在执行结束请求的任务 ID */
            killingId: null
        };
    },

    watch: {
        logs: {
            handler: function () {
                this.$nextTick(() => {
                    this.scrollToBottom();
                });
            },
            deep: false
        },
        visible: function () {
            this.syncPolling();
        },
        taskExpandedId: function () {
            // 每次重新展开都重新贴底，不然看到的是上次留下的滚动位置
            this._logPinned = true;
        }
    },

    mounted: function () {
        var self = this;
        this._onResize = function () {
            self.isMobile = window.innerWidth < 769;
        };
        window.addEventListener('resize', this._onResize);

        // 投放目标此时已在文档中（mounted 为 post-flush），开启 Teleport
        this.anchorReady = !!document.querySelector('.message-area-wrapper');

        // 订阅 Agent Log 信号与本地事件（wsBus 由 app.provide 注入）
        if (this.wsBus) {
            this._unsubAgentLog = this.wsBus.on('AGENT_LOG', (payload) => {
                try {
                    this.onAgentLog(JSON.parse(payload));
                } catch (e) {
                    console.error('解析 AGENT_LOG 失败:', e);
                }
            });
            this._unsubClear = this.wsBus.on('agent-log:clear', () => this.clearLogs());
        }
    },

    beforeUnmount: function () {
        if (this._onResize) {
            window.removeEventListener('resize', this._onResize);
        }
        if (this._unsubAgentLog) { this._unsubAgentLog(); this._unsubAgentLog = null; }
        if (this._unsubClear) { this._unsubClear(); this._unsubClear = null; }
        this.stopPolling();
        this.clearConfirmTimer();
    },

    methods: {
        /* ---- 公开方法 ---- */
        toggle: function () {
            this.visible = !this.visible;
        },

        open: function () {
            this.visible = true;
        },

        close: function () {
            this.visible = false;
        },

        clearLogs: function () {
            this.logs = [];
            this.expandedId = null;
            this.tasks = [];
            this.taskLogs = {};
            this.taskExpandedId = null;
            this.confirmingId = null;
        },

        /* ---- 页签 ---- */
        switchTab: function (tab) {
            if (this.activeTab === tab) return;
            this.activeTab = tab;
        },

        /* ---- Agent Log 内部 ---- */
        onAgentLog: function (entry) {
            if (!entry) return;
            // 只记录当前会话的日志；无 sessionStore（插件页）时不作过滤
            if (this.sessionStore) {
                var current = this.sessionStore.state.currentSession;
                var currentId = current ? current.id : undefined;
                if (entry.sessionId !== currentId) return;
            }
            this.logs.push(entry);
        },

        toggleEntry: function (entry) {
            if (!entry.content) return;
            this.expandedId = this.expandedId === entry.id ? null : entry.id;
        },

        scrollToBottom: function () {
            var el = this.$refs.logList;
            if (el) {
                el.scrollTop = el.scrollHeight;
            }
        },

        formatTime: function (ts) {
            if (!ts) return '';
            var d = new Date(ts);
            var h = String(d.getHours()).padStart(2, '0');
            var m = String(d.getMinutes()).padStart(2, '0');
            var s = String(d.getSeconds()).padStart(2, '0');
            return h + ':' + m + ':' + s;
        },

        /* ---- 后台任务：轮询 ---- */

        /** 当前会话 ID；插件页没有 sessionStore 时返回 null，任务页自然为空 */
        currentSessionId: function () {
            if (!this.sessionStore) return null;
            var current = this.sessionStore.state.currentSession;
            return current ? current.id : null;
        },

        /**
         * 面板一打开就开始轮询，关掉就停。
         * <p>不在页签上做区分：停在 Agent Log 页时也要把「后台任务」的徽标数刷准，
         * 切过去的瞬间就得有数据，不然会出现「明明有任务在跑，页签却是空的」。</p>
         */
        syncPolling: function () {
            if (this.visible) {
                this.startPolling();
            } else {
                this.stopPolling();
            }
        },

        startPolling: function () {
            if (this._pollTimer) return;
            this.pollTasks();
            this._pollTimer = setInterval(() => {
                this.pollTasks();
            }, 1000);
        },

        stopPolling: function () {
            if (this._pollTimer) {
                clearInterval(this._pollTimer);
                this._pollTimer = null;
            }
        },

        /** 拉一次任务列表，顺带刷新运行时长与展开任务的日志 */
        pollTasks: async function () {
            // 上一轮还没回来就跳过这一轮：慢请求堆积只会让面板越刷越卡
            if (this._polling) return;
            this._polling = true;
            try {
                await this.pollTasksInner();
            } finally {
                this._polling = false;
            }
        },

        pollTasksInner: async function () {
            this.taskNow = Date.now();
            var sid = this.currentSessionId();
            if (!sid) {
                this.tasks = [];
                return;
            }
            var res;
            try {
                res = await API.commandTask.list(sid);
            } catch (e) {
                // 网络抖动不该在面板上刷错误，下一轮自然会补上
                return;
            }
            if (!res || res.status !== 200) return;

            var list = res.data || [];
            var alive = {};
            list.forEach(function (task) { alive[task.id] = true; });

            var self = this;
            Object.keys(this.taskLogs).forEach(function (id) {
                if (!alive[id]) delete self.taskLogs[id];
            });
            if (this.taskExpandedId && !alive[this.taskExpandedId]) {
                this.taskExpandedId = null;
            }
            this.tasks = list;

            if (this.taskExpandedId) {
                await this.pollTaskLog(this.taskExpandedId);
            }
        },

        /** 增量拉某个任务的日志：reset 或首次时整段替换，否则追加到已有内容后面 */
        pollTaskLog: async function (id) {
            var sid = this.currentSessionId();
            if (!sid) return;
            var cached = this.taskLogs[id];
            var offset = cached ? cached.offset : null;
            var res;
            try {
                res = await API.commandTask.log(sid, id, offset);
            } catch (e) {
                return;
            }
            if (!res || res.status !== 200) return;

            var chunk = res.data || {};
            var incoming = chunk.content || '';
            var content = (chunk.reset || !cached) ? incoming : ((cached.content || '') + incoming);

            // 面板不是日志阅读器：超过上限就只留尾部，完整内容去会话文件 logs/ 里翻
            if (content.length > this.MAX_LOG_CHARS) {
                content = '…（更早的输出已省略，完整内容见会话文件 logs/ 目录）\n'
                    + content.slice(-this.MAX_LOG_CHARS);
            }
            this.taskLogs[id] = { content: content, offset: chunk.offset || 0 };

            var self = this;
            this.$nextTick(function () {
                self.scrollTaskLogToBottom();
            });
        },

        /* ---- 后台任务：交互 ---- */

        toggleTaskLog: function (task) {
            this.taskExpandedId = this.taskExpandedId === task.id ? null : task.id;
            if (this.taskExpandedId) {
                this.pollTaskLog(task.id);
            }
        },

        /** 用户往上翻看历史时不强行拽回底部，只有本来就贴着底部才自动跟 */
        onTaskLogScroll: function () {
            var el = this.$refs.taskLogView;
            if (!el) return;
            this._logPinned = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        },

        scrollTaskLogToBottom: function () {
            var el = this.$refs.taskLogView;
            if (!el) return;
            if (this._logPinned === false) return;
            el.scrollTop = el.scrollHeight;
        },

        /**
         * 结束任务：第一次点只是「举起手」，3 秒内再点一次才真杀。
         * 结束是不可逆的（编译、写文件都可能被拦腰砍断），值得多问一句。
         */
        killTask: function (task) {
            if (this.killingId) return;
            if (this.confirmingId !== task.id) {
                this.confirmingId = task.id;
                this.clearConfirmTimer();
                this._confirmTimer = setTimeout(() => {
                    this.confirmingId = null;
                }, 3000);
                return;
            }
            this.clearConfirmTimer();
            this.confirmingId = null;
            this.doKill(task);
        },

        doKill: async function (task) {
            var sid = this.currentSessionId();
            if (!sid) return;
            this.killingId = task.id;
            try {
                var res = await API.commandTask.kill(sid, task.id);
                if (res && res.status === 200) {
                    this.tasks = this.tasks.filter(function (t) { return t.id !== task.id; });
                    delete this.taskLogs[task.id];
                    if (this.taskExpandedId === task.id) {
                        this.taskExpandedId = null;
                    }
                } else {
                    // 失败多半是进程自己已经退了，刷一下列表让它自然消失
                    this.pollTasks();
                }
            } catch (e) {
                this.pollTasks();
            } finally {
                this.killingId = null;
            }
        },

        clearConfirmTimer: function () {
            if (this._confirmTimer) {
                clearTimeout(this._confirmTimer);
                this._confirmTimer = null;
            }
        },

        /** 运行时长：秒级就够，超过一分钟改成 分+秒，超过一小时改成 时+分 */
        formatDuration: function (startTime, now) {
            if (!startTime) return '';
            var ms = now - startTime;
            if (ms < 0) ms = 0;
            var totalSeconds = Math.floor(ms / 1000);
            if (totalSeconds < 60) return totalSeconds + 's';
            var minutes = Math.floor(totalSeconds / 60);
            if (minutes < 60) return minutes + 'm ' + (totalSeconds % 60) + 's';
            return Math.floor(minutes / 60) + 'h ' + (minutes % 60) + 'm';
        }
    },

    created: function () {
        // 面板里最多保留的日志字符数（约 100KB），超出只留尾部
        this.MAX_LOG_CHARS = 100000;
        this._logPinned = true;
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            var self = this;
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};

// 自注册进顶栏右栏槽（registry 定义于 message-topbar.js，须先于本文件加载）
if (typeof TopbarPlugins !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-right', AgentLogSidebar, 0);
}
