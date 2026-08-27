/**
 * 聊天页侧边栏组件（会话列表 + 右键菜单 + 桌面折叠 / 移动端抽屉）
 *
 * 组件内部持有 sessions[] 作为唯一数据源，父组件通过 ref 调用 refresh()/upsert() 同步数据。
 *
 * Props:
 *   currentSession  — Object   当前选中的会话
 *   mainColor         — String   主题色
 *   collapsed         — Boolean  桌面端侧边栏是否折叠
 *
 * Emits:
 *   select-session(session)     — 点击会话
 *   create-session()            — 点击"新对话"
 *   session-deleted(sessionId)  — 会话已被删除（组件已从内部数组移除）
 *   toggle-collapsed()          — 桌面端折叠 / 移动端滑出
 *
 * 公开方法（通过 ref 调用）：
 *   toggle()               — 切换侧边栏（桌面折叠 or 移动抽屉）
 *   close()                — 关闭移动端抽屉
 *   refresh()              — 重新从服务端拉取 sessions
 *   upsert(session)        — 新增或更新一个会话条目
 *   getSessionById(id)     — 按 id 查找会话对象（返回引用）
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
                   @click="$emit('create-session')">
            <i data-lucide="square-plus"></i>
            <span style="margin-left: 10px">新对话</span>
        </el-button>
        <div class="sidebar-session-list">
            <div class="sidebar-session-item"
                 v-for="session in sessions"
                 :key="session.id"
                 :class="{ pro: session.enablePro, unreviewed: session.unreviewed }"
                 :style="currentSession.id === session.id ? {backgroundColor: 'white', borderRadius: '10px', padding: '5px 5px 15px 5px',  borderBottomColor: 'white'} : {}"
                 @click="$emit('select-session', session)"
                 @contextmenu.prevent="showContextMenu($event, session)">
                {{ session.name }}
            </div>
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
        </div>
        <div class="sidebar-footer">
            <el-button class="sidebar-settings"
                       @click="goSettings"
                       style="color: #333"
                       type="text"
                       title="设置">
                <i ref="settings" style="width: 25px; height: 25px" class="sidebar-settings-icon" data-lucide="settings"></i>
            </el-button>
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
                    <div class="entry-item-title">{{ item.title }}</div>
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
        currentSession: { type: Object, default: null },
        mainColor: { type: String, default: 'lightsalmon' },
        collapsed: { type: Boolean, default: false }
    },

    emits: ['select-session', 'create-session', 'on-delete-session', 'change-session-loading', 'toggle-collapsed'],

    data: function () {
        return {
            /** 会话列表（组件内部唯一数据源） */
            sessions: [],
            /** 当前列表模式：chat / cron */
            listMode: 'chat',
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
        this.refresh();
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
         * 从服务端重新获取会话列表（根据当前 listMode）。
         * 由父组件在 WebSocket 事件（###START###）时通过 ref 调用。
         */
        refresh: async function () {
            try {
                let result = await API.session.getAll(this.listMode);
                if (result.status === 200) {
                    this.sessions = result.data;
                }
            } catch (error) {
                console.error('获取会话列表失败:', error);
            }
        },

        /**
         * 切换列表模式：chat ↔ cron
         */
        toggleListMode: function () {
            this.listMode = this.listMode === 'chat' ? 'cron' : 'chat';
            this.refresh();
        },

        /**
         * 新增或更新一个会话条目。
         * 由父组件在收到 ###UPDATE_SESSION### 时通过 ref 调用。
         * @param {object} session — { id, name, ... }
         * @returns {object} 内部数组中的会话对象引用
         */
        upsert: function (session) {
            let existing = this.sessions.find(function (s) { return s.id === session.id; });
            if (existing) {
                Object.assign(existing, session);
                return existing;
            } else {
                this.sessions.push(session);
                return this.sessions[this.sessions.length - 1];
            }
        },

        get: function (session) {
            if (! session) return session;
            let existing = this.sessions.find(function (s) { return s.id === session.id; });
            if (! existing) {
                this.sessions.push(session);
                existing = session;
            }
            return existing;
        },

        /**
         * 按 id 查找会话对象。
         * @param {string} id
         * @returns {object|undefined} 会话对象引用
         */
        getSessionById: function (id) {
            return this.sessions.find(function (s) { return s.id === id; });
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
         * 删除会话：弹出确认框 → 调用接口 → 从内部数组移除 → 通知父组件
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
            }).then(async function () {
                try {
                    let result = await API.session.delete(session.id);
                    if (result.status === 200) {
                        ElementPlus.ElMessage.success('会话已删除');
                        // 从内部数组移除
                        var idx = self.sessions.findIndex(function (s) { return s.id === session.id; });
                        if (idx !== -1) {
                            self.sessions.splice(idx, 1);
                        }
                        self.$emit('on-delete-session', session);
                    } else {
                        ElementPlus.ElMessage.error(result.message || '删除会话失败');
                    }
                } catch (error) {
                    ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                    console.error('删除会话失败:', error);
                }
            }).catch(function () { /* 用户取消 */ });
        },

        /**
         * 切换会话的 Pro 模式（普通 ↔ 高级），直接切换无需确认
         */
        toggleProMode: async function (session) {
            var self = this;
            self.closeContextMenu();
            try {
                var result = await API.session.togglePro(session.id);
                if (result.status === 200) {
                    // 更新内部数组中的会话对象
                    var target = self.sessions.find(function (s) { return s.id === session.id; });
                    if (target) {
                        Object.assign(target, result.data);
                    }
                    // 同步更新 currentSession
                    if (self.currentSession && self.currentSession.id === session.id) {
                        Object.assign(self.currentSession, result.data);
                    }
                } else {
                    ElementPlus.ElMessage.error(result.message || '切换模式失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error('切换模式失败:', error);
            }
        },

        /**
         * 切换会话的无审查模式（审查中 ↔ 无审查）。
         * 开启（进入无审查）时先弹确认框，提示将关闭该会话所有工具确认与 AI 危险审查，防止误触；关闭直接切换。
         */
        toggleUnreviewed: function (session) {
            var self = this;
            self.closeContextMenu();
            var enabling = !session.unreviewed;
            var doToggle = async function () {
                try {
                    var result = await API.session.toggleUnreviewed(session.id);
                    if (result.status === 200) {
                        // 更新内部数组中的会话对象
                        var target = self.sessions.find(function (s) { return s.id === session.id; });
                        if (target) {
                            Object.assign(target, result.data);
                        }
                        // 同步更新 currentSession
                        if (self.currentSession && self.currentSession.id === session.id) {
                            Object.assign(self.currentSession, result.data);
                        }
                        ElementPlus.ElMessage.success(enabling ? '已开启无审查模式' : '已关闭无审查模式');
                    } else {
                        ElementPlus.ElMessage.error(result.message || '切换无审查模式失败');
                    }
                } catch (error) {
                    ElementPlus.ElMessage.error('网络请求失败');
                    console.error('切换无审查模式失败:', error);
                }
            };
            if (!enabling) {
                // 关闭无审查：直接切换
                doToggle();
                return;
            }
            // 开启无审查：先弹确认，防止误触
            this.$refs.confirmDialog.show({
                title: '无审查模式确认',
                message: '开启后该会话内所有工具操作（文件写入/删除、命令执行、文件下载、联网探索等）将不再弹确认，也不做 AI 危险审查。请谨慎操作，确定开启吗？',
                confirmText: '开启',
                cancelText: '取消',
                type: 'warning'
            }).then(doToggle).catch(function () { /* 用户取消 */ });
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
                message: '确定从该会话移除知识条目「' + item.title + '」吗？',
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
        isNewSession: function () {
            return this.currentSession && !this.currentSession.id;
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
