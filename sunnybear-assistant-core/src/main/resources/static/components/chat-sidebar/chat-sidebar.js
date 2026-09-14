/**
 * 聊天页侧边栏组件（会话列表 + 右键菜单 + 桌面折叠 / 移动端抽屉）
 *
 * 纯视图：会话列表与分页状态统一由 SessionStore 持有，本组件只负责渲染与交互，
 * 数据操作（刷新/翻页/增删/Pro/无审查）一律调用 store 方法。
 *
 * 开合由 WsBus 本地事件驱动：订阅 'sidebar:toggle'（顶部菜单按钮）与
 * 'sidebar:close'（store 切/新建会话时）。桌面端的折叠态仍通过 emit 交父级布局。
 *
 * Props:
 *   mainColor         — String   主题色
 *   collapsed         — Boolean  桌面端侧边栏是否折叠
 *
 * Injects:
 *   sessionStore      — 会话/消息仓库（可选，插件页降级）
 *   wsBus             — WebSocket 消息总线（可选）
 *
 * Emits:
 *   toggle-collapsed()  — 桌面端折叠 / 移动端滑出
 *
 * 公开方法（通过 ref 调用）：
 *   toggle()               — 切换侧边栏（桌面折叠 or 移动抽屉）
 *   close()                — 关闭移动端抽屉
 */
const ChatSidebar = {
    name: 'ChatSidebar',

    template: `
    <div class="app-sidebar"
         :class="{ 'mobile-open': sidebarOpen, 'collapsed': collapsed }"
         :style="{backgroundColor: mainColor}">
        <el-button :disabled="isNewSession"
                   class="sidebar-new-chat-button"
                   :color="mainColor"
                   plain
                   @click="createSession">
            <i data-lucide="square-plus"></i>
            <span style="margin-left: 10px">新对话</span>
        </el-button>
        <div class="sidebar-session-list"
             ref="sessionList"
             v-infinite-scroll="loadMore">
            <div class="sidebar-session-item"
                 v-for="session in sessions"
                 :key="session.id"
                 :class="{ pro: session.enablePro, unreviewed: session.unreviewed }"
                 :style="currentSession.id === session.id ? {backgroundColor: 'white', borderRadius: '10px', padding: '5px 5px 15px 5px',  borderBottomColor: 'white'} : {}"
                 @click="selectSession(session)"
                 @contextmenu.prevent="showContextMenu($event, session)">
                {{ session.name }}
            </div>
            <div v-if="sessionsLoadingMore"
                 style="color:rgba(255,255,255,.75);font-size:13px;padding:6px 0 16px;">加载中…</div>
        </div>
        <!-- 右键上下文菜单 -->
        <div v-if="contextMenu.visible"
             class="session-context-menu"
             :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
             @click.stop>
            <div class="session-context-menu-item" @click="deleteSession(contextMenu.session)">
                <i data-lucide="trash-2" style="width: 16px; height: 16px;"></i>
                <span>删除会话</span>
            </div>
            <div class="session-context-menu-item" @click="toggleProMode(contextMenu.session)">
                <i data-lucide="zap" style="width: 16px; height: 16px;"></i>
                <span>{{ contextMenu.session.enablePro ? '当前：高级' : '当前：普通' }}</span>
            </div>
            <div class="session-context-menu-item" @click="toggleUnreviewed(contextMenu.session)">
                <i data-lucide="shield-off" style="width: 16px; height: 16px;"></i>
                <span>{{ contextMenu.session.unreviewed ? '当前：无审查' : '当前：审查中' }}</span>
            </div>
            <div class="session-context-menu-item" @click="openSessionKnowledge(contextMenu.session)">
                <i data-lucide="database" style="width: 16px; height: 16px;"></i>
                <span>查看知识库</span>
            </div>
            <div class="session-context-menu-item" @click="exportSession(contextMenu.session)">
                <i data-lucide="download" style="width: 16px; height: 16px;"></i>
                <span>导出对话</span>
            </div>
        </div>
        <div class="sidebar-footer">
            <div class="sidebar-footer-left">
                <el-button class="sidebar-settings"
                           @click="goSettings"
                           style="color: #333"
                           type="text"
                           title="设置">
                    <i ref="settings" style="width: 25px; height: 25px" class="sidebar-settings-icon" data-lucide="settings"></i>
                </el-button>
                <el-button class="sidebar-settings"
                           @click="goRouter"
                           style="color: #333"
                           type="text"
                           title="页面导航">
                    <i ref="router" style="width: 25px; height: 25px" class="sidebar-settings-icon" data-lucide="layout-grid"></i>
                </el-button>
            </div>
            <!-- 定时器 / 对话 切换 -->
            <button class="sidebar-list-mode-toggle"
                    :title="listMode === 'chat' ? '切换到定时器会话' : '切换到对话会话'"
                    @click="toggleListMode">
                <span class="list-mode-icon" :class="{ active: listMode === 'chat' }">
                    <i data-lucide="message-circle" style="width:20px;height:20px"></i>
                </span>
                <span class="list-mode-icon" :class="{ active: listMode === 'cron' }">
                    <i data-lucide="clock" style="width:20px;height:20px"></i>
                </span>
            </button>
        </div>
    </div>
    <!-- 移动端侧边栏遮罩 -->
    <div class="sidebar-overlay" :class="{ visible: sidebarOpen }" @click="closeSidebar"></div>

    <!-- 通用确认弹窗 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>

    <!-- 会话知识库查看对话框（样式参考 settings 的知识条目管理） -->
    <el-dialog v-model="sessionKnowledgeDialog" title="" width="800px"
               class="session-knowledge-dialog" :close-on-click-modal="false" destroy-on-close>
        <template #header>
            <div class="dialog-header-wrap">
                <i data-lucide="database" style="width:20px;height:20px"></i>
                <span>会话知识库</span>
                <span style="font-size:13px;font-weight:400;color:var(--text-secondary)">
                    {{ sessionKnowledgeSession ? ' · ' + sessionKnowledgeSession.name : '' }}
                </span>
            </div>
        </template>
        <div v-if="sessionKnowledgeLoading" style="text-align:center;padding:40px;color:#909399">加载中...</div>
        <div v-else-if="sessionKnowledgeList.length === 0" style="text-align:center;padding:40px;color:#909399">该会话暂无注入的知识库内容</div>
        <div v-else class="entry-list">
            <div v-for="item in sessionKnowledgeList" :key="item.id" class="entry-item">
                <div class="entry-item-body">
                    <div class="entry-item-title">{{ item.intro }}</div>
                    <div class="entry-item-content">{{ item.content }}</div>
                    <div class="entry-item-time">{{ (item.createTime || '').replace('T', ' ') }}</div>
                </div>
                <div class="entry-item-actions">
                    <button type="button" class="entry-action-btn delete" @click="removeSessionKnowledgeItem(item)" title="从会话移除">
                        <i data-lucide="trash-2" style="width:15px;height:15px"></i>
                    </button>
                </div>
            </div>
        </div>
        <template #footer>
            <div class="dialog-footer">
                <button type="button" class="dialog-btn dialog-btn-cancel" @click="sessionKnowledgeDialog = false">关闭</button>
            </div>
        </template>
    </el-dialog>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' },
        collapsed: { type: Boolean, default: false }
    },

    emits: ['toggle-collapsed'],

    inject: {
        // 可选注入：未提供时降级为 null
        sessionStore: { default: null },
        wsBus: { default: null }
    },

    data: function () {
        return {
            sidebarOpen: false,
            contextMenu: {
                visible: false,
                x: 0,
                y: 0,
                session: null
            },
            /** 会话知识库查看对话框 */
            sessionKnowledgeDialog: false,
            sessionKnowledgeList: [],
            sessionKnowledgeSession: null,
            sessionKnowledgeLoading: false
        };
    },

    mounted: function () {
        var self = this;
        if (this.sessionStore) {
            this.sessionStore.refreshSessions().finally(function () { self.ensureScrollable(); });
        }
        // 兄弟组件（message-topbar）/ store 经 WsBus 本地事件驱动侧边栏开合
        if (this.wsBus) {
            this._unsubSidebarToggle = this.wsBus.on('sidebar:toggle', function () { self.toggle(); });
            this._unsubSidebarClose = this.wsBus.on('sidebar:close', function () { self.close(); });
        }
    },

    beforeUnmount: function () {
        if (this._unsubSidebarToggle) { this._unsubSidebarToggle(); this._unsubSidebarToggle = null; }
        if (this._unsubSidebarClose) { this._unsubSidebarClose(); this._unsubSidebarClose = null; }
    },

    methods: {
        /* ---- 公开方法 ---- */

        /**
         * 切换侧边栏：桌面端折叠/展开，移动端滑出抽屉
         */
        toggle: function () {
            if (window.innerWidth >= 769) {
                this.$emit('toggle-collapsed');
            } else {
                this.sidebarOpen = !this.sidebarOpen;
            }
        },

        /**
         * 关闭侧边栏（移动端抽屉 + 桌面端折叠通用）
         */
        close: function () {
            this.sidebarOpen = false;
        },

        /**
         * 跳转到设置页面（不依赖父页面，组件内直接跳转）
         */
        goSettings: function () {
            window.location.href = API.BASE_PATH + 'settings.html';
        },

        /**
         * 跳转到页面导航（router）
         */
        goRouter: function () {
            window.location.href = API.BASE_PATH + 'router.html';
        },

        /** 点击会话：切换当前会话（委托 store） */
        selectSession: function (session) {
            if (this.sessionStore) this.sessionStore.selectSession(session);
        },

        /** 点击"新对话"（委托 store） */
        createSession: function () {
            if (this.sessionStore) this.sessionStore.createSession();
        },

        /** 切换列表模式：chat ↔ cron（委托 store） */
        toggleListMode: function () {
            if (this.sessionStore) this.sessionStore.toggleSessionListMode();
        },

        /** 触底加载更早一页（由 v-infinite-scroll 指令触发，委托 store） */
        loadMore: function () {
            // 返回 Promise 以便指令在请求期间上锁，避免重复触发
            if (!this.sessionStore) return Promise.resolve();
            return this.sessionStore.loadMoreSessions();
        },

        /**
         * 列表不足一屏（容器还没出现滚动条）时自动续拉，直到填满可滚动或没有更多。
         */
        ensureScrollable: function () {
            const self = this;
            if (!this.sessionsHasMore || this.sessionsLoadingMore) return;
            this.$nextTick(function () {
                const el = self.$refs.sessionList;
                // 容器不可见/无高度（折叠、抽屉收起）时不自动补页
                if (!el || !el.clientHeight || self.sessionsLoadingMore || !self.sessionsHasMore) return;
                if (el.scrollHeight - el.clientHeight < 24) {
                    self.loadMore();
                }
            });
        },

        /* ---- 内部方法 ---- */

        /**
         * 关闭移动端抽屉（由遮罩点击触发）
         */
        closeSidebar: function () {
            this.sidebarOpen = false;
        },

        /**
         * 显示会话右键上下文菜单
         */
        showContextMenu: function (event, session) {
            this.contextMenu.visible = true;
            this.contextMenu.x = event.clientX;
            this.contextMenu.y = event.clientY;
            this.contextMenu.session = session;
            var self = this;
            this.$nextTick(function () {
                document.addEventListener('click', self.closeContextMenu);
            });
        },

        /**
         * 删除会话：弹出确认框 → 委托 store 调接口并从列表移除
         */
        deleteSession: function (session) {
            var self = this;
            self.closeContextMenu();
            this.$refs.confirmDialog.show({
                title: '删除确认',
                message: '确定要删除会话 "' + session.name + '" 吗？此操作不可恢复。',
                confirmText: '删除',
                cancelText: '取消',
                type: 'warning'
            }).then(function () {
                if (self.sessionStore) self.sessionStore.deleteSession(session);
            }).catch(function () { /* 用户取消 */ });
        },

        /* ---- 导出对话（后端生成文件，前端触发下载） ---- */

        /**
         * 导出会话对话记录：调用后端 /message/export 拉取生成文件，前端触发浏览器下载
         * @param {object} session - 要导出的会话对象
         */
        exportSession: async function (session) {
            var self = this;
            self.closeContextMenu();
            if (!session || !session.id) {
                ElementPlus.ElMessage.warning('请先选择一个会话');
                return;
            }
            var url = API.BASE_PATH + 'message/export?sessionId=' + encodeURIComponent(session.id) + '&format=markdown';
            try {
                var response = await fetch(url);
                if (!response.ok) {
                    var err = null;
                    try { err = await response.json(); } catch (e) { /* 非 JSON 错误体 */ }
                    ElementPlus.ElMessage.error((err && err.message) || '导出失败');
                    return;
                }
                var blob = await response.blob();
                var downloadUrl = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = downloadUrl;
                a.download = self.sanitizeExportName(session.name || '对话') + '_' + self.formatExportStamp(new Date()) + '.md';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(downloadUrl);
                ElementPlus.ElMessage.success('已导出');
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('导出会话失败:', error);
            }
        },

        /**
         * 清洗导出文件名中的非法字符
         */
        sanitizeExportName: function (name) {
            return String(name).replace(/[\\/:*?"<>|\r\n]+/g, '_').trim();
        },

        /**
         * 生成导出文件名时间戳：yyyyMMdd_HHmmss
         */
        formatExportStamp: function (date) {
            var p = function (n) { return String(n).padStart(2, '0'); };
            return date.getFullYear() + p(date.getMonth() + 1) + p(date.getDate())
                + '_' + p(date.getHours()) + p(date.getMinutes()) + p(date.getSeconds());
        },

        /**
         * 切换会话的 Pro 模式（普通 ↔ 高级），直接切换无需确认（委托 store）
         */
        toggleProMode: function (session) {
            this.closeContextMenu();
            if (this.sessionStore) this.sessionStore.toggleSessionPro(session);
        },

        /**
         * 切换会话的无审查模式（审查中 ↔ 无审查）。
         * 开启（进入无审查）时先弹确认框，提示将关闭该会话所有工具确认与 AI 危险审查，防止误触；关闭直接切换。
         */
        toggleUnreviewed: function (session) {
            var self = this;
            self.closeContextMenu();
            if (!self.sessionStore) return;
            var enabling = !session.unreviewed;
            if (!enabling) {
                // 关闭无审查：直接切换
                self.sessionStore.toggleSessionUnreviewed(session);
                return;
            }
            // 开启无审查：先弹确认，防止误触
            this.$refs.confirmDialog.show({
                title: '无审查模式确认',
                message: '开启后该会话内所有工具操作（文件写入/删除、命令执行、文件下载、联网探索等）将不再弹确认，也不做 AI 危险审查。请谨慎操作，确定开启吗？',
                confirmText: '开启',
                cancelText: '取消',
                type: 'warning'
            }).then(function () {
                self.sessionStore.toggleSessionUnreviewed(session);
            }).catch(function () { /* 用户取消 */ });
        },

        /**
         * 查看会话已注入的知识库条目：弹出知识库对话框
         */
        openSessionKnowledge: function (session) {
            this.closeContextMenu();
            this.sessionKnowledgeSession = session;
            this.sessionKnowledgeList = [];
            this.sessionKnowledgeDialog = true;
            // 对话框内容挂载后手动刷新 lucide 图标（与 settings 打开弹窗的做法一致）
            this.$nextTick(function () { lucide.createIcons(); });
            this.loadSessionKnowledge();
        },

        /**
         * 加载当前查看会话的已注入知识条目
         */
        loadSessionKnowledge: async function () {
            if (!this.sessionKnowledgeSession) return;
            this.sessionKnowledgeLoading = true;
            try {
                var result = await API.knowledge.sessionList(this.sessionKnowledgeSession.id);
                this.sessionKnowledgeList = (result.status === 200 && result.data) ? result.data : [];
            } catch (error) {
                console.error('获取会话知识库失败:', error);
                this.sessionKnowledgeList = [];
            } finally {
                this.sessionKnowledgeLoading = false;
                // 列表 v-else 渲染完成后刷新 lucide 图标
                this.$nextTick(function () { lucide.createIcons(); });
            }
        },

        /**
         * 从会话中移除一条知识条目（不影响知识条目本身），复用项目通用确认弹窗
         */
        removeSessionKnowledgeItem: function (item) {
            var self = this;
            var session = this.sessionKnowledgeSession;
            if (!session) return;
            this.$refs.confirmDialog.show({
                title: '移除确认',
                message: '确定从该会话移除知识条目「' + item.intro + '」吗？',
                confirmText: '移除',
                cancelText: '取消',
                type: 'warning'
            }).then(async function () {
                try {
                    var result = await API.knowledge.sessionRemove(session.id, item.id);
                    if (result.status === 200) {
                        ElementPlus.ElMessage.success('已移除');
                        await self.loadSessionKnowledge();
                    } else {
                        ElementPlus.ElMessage.error(result.message || '移除失败');
                    }
                } catch (error) {
                    ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                    console.error('移除会话知识失败:', error);
                }
            }).catch(function () { /* 用户取消 */ });
        },

        /**
         * 关闭右键上下文菜单
         */
        closeContextMenu: function () {
            this.contextMenu.visible = false;
            this.contextMenu.session = null;
            document.removeEventListener('click', this.closeContextMenu);
        }
    },

    computed: {
        // 以下均来自 SessionStore（未注入时降级为空/默认，保证插件页不报错）
        sessions: function () {
            return this.sessionStore ? this.sessionStore.sessions : [];
        },
        sessionsHasMore: function () {
            return this.sessionStore ? this.sessionStore.sessionsHasMore : false;
        },
        sessionsLoadingMore: function () {
            return this.sessionStore ? this.sessionStore.sessionsLoadingMore : false;
        },
        listMode: function () {
            return this.sessionStore ? this.sessionStore.sessionListMode : 'chat';
        },
        // 当前会话：优先取注入的 sessionStore，插件页无法注入时回退空对象
        currentSession: function () {
            return this.sessionStore ? this.sessionStore.state.currentSession : {};
        },
        isNewSession: function () {
            return this.currentSession && !this.currentSession.id;
        }
    },

    watch: {
        // 列表长度变化（刷新/翻页）后确保容器可滚动
        'sessions.length': function () {
            this.ensureScrollable();
        }
    },

    /* ---- 图标刷新 ---- */
    updated: function () {
        if (typeof lucide !== 'undefined') {
            var self = this;
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
