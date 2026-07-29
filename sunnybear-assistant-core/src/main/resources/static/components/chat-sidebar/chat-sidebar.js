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
 *   go-settings()               — 点击设置按钮
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
                 :class="{ pro: session.enablePro }"
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
        </div>
        <div class="sidebar-footer">
            <el-button class="sidebar-settings"
                       @click="$emit('go-settings')"
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
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>`,

    props: {
        currentSession: { type: Object, default: null },
        mainColor: { type: String, default: 'lightsalmon' },
        collapsed: { type: Boolean, default: false }
    },

    emits: ['select-session', 'create-session', 'on-delete-session', 'change-session-loading', 'go-settings', 'toggle-collapsed'],

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
            }
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
