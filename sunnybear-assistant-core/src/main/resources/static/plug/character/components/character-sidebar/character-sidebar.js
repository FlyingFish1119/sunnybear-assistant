/**
 * 角色扮演页侧边栏组件（会话列表 + 右键菜单 + 桌面折叠 / 移动端抽屉）
 *
 * 与 chat-sidebar 结构相同，但使用角色专属 API（API.character.getSessions）。
 * 组件内部持有 sessions[] 作为唯一数据源，父组件通过 ref 调用 refresh()/upsert() 同步数据。
 *
 * Props:
 *   currentSession  — Object   当前选中的会话
 *   mainColor       — String   主题色
 *   collapsed       — Boolean  桌面端侧边栏是否折叠
 *   characterId     — String   角色 ID（用于获取角色专属会话列表）
 *
 * Emits:
 *   select-session(session)     — 点击会话
 *   create-session()            — 点击"新对话"
 *   on-delete-session(session)  — 会话已被删除（组件已从内部数组移除）
 *   toggle-collapsed()          — 桌面端折叠 / 移动端滑出
 *
 * 公开方法（通过 ref 调用）：
 *   toggle()               — 切换侧边栏（桌面折叠 or 移动抽屉）
 *   close()                — 关闭移动端抽屉
 *   refresh()              — 重新从服务端拉取 sessions（角色专属）
 *   upsert(session)        — 新增或更新一个会话条目
 *   getSessionById(id)     — 按 id 查找会话对象（返回引用）
 */
const CharacterSidebar = {
    name: 'CharacterSidebar',

    template: `
    <div class="app-sidebar"
         :class="{ 'mobile-open': sidebarOpen, 'collapsed': collapsed }"
         :style="{backgroundColor: mainColor}">
        <button class="sidebar-new-chat-button"
                :disabled="isNewSession"
                @click="$emit('create-session')">
            <i data-lucide="square-plus"></i>
            <span>新对话</span>
        </button>
        <div class="sidebar-session-list">
            <div v-if="sessions.length === 0" class="sidebar-empty">暂无对话</div>
            <div class="sidebar-session-item"
                 v-for="session in sessions"
                 :key="session.id"
                 :class="{ active: currentSession.id === session.id }"
                 @click="$emit('select-session', session)"
                 @contextmenu.prevent="showContextMenu($event, session)">
                <span class="sidebar-session-name">{{ session.name }}</span>
                <span v-if="sessionTokenTotal(session) != null"
                      class="sidebar-session-tokens"
                      title="本会话累计 token 消耗">{{ formatTokens(sessionTokenTotal(session)) }}</span>
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
            <div class="session-context-menu-item" @click="exportSession(contextMenu.session)">
                <i data-lucide="download" style="width: 16px; height: 16px;"></i>
                <span>导出对话</span>
            </div>
        </div>
        <div class="sidebar-footer">
            <div class="sidebar-footer-left">
                <button class="sidebar-icon-btn" @click="goSettings" title="设置">
                    <i data-lucide="settings"></i>
                </button>
                <button class="sidebar-icon-btn" @click="goRouter" title="页面导航">
                    <i data-lucide="layout-grid"></i>
                </button>
            </div>
        </div>
    </div>
    <!-- 移动端侧边栏遮罩 -->
    <div class="sidebar-overlay" :class="{ visible: sidebarOpen }" @click="closeSidebar"></div>

    <!-- 通用确认弹窗 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>`,

    props: {
        currentSession: { type: Object, default: null },
        mainColor: { type: String, default: 'lightsalmon' },
        collapsed: { type: Boolean, default: false },
        /** 角色 ID。为 null 时跳过 refresh()，等加载完成后再调用。 */
        characterId: { type: String, default: null }
    },

    emits: ['select-session', 'create-session', 'on-delete-session', 'toggle-collapsed'],

    data: function () {
        return {
            /** 会话列表（组件内部唯一数据源） */
            sessions: [],
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
         * 跳转到设置页面（不依赖父页面，组件内直接跳转）
         */
        goSettings: function () {
            window.location.href = API.BASE_PATH + 'plug/character/character_settings.html';
        },

        /**
         * 跳转到页面导航（router）
         */
        goRouter: function () {
            window.location.href = API.BASE_PATH + 'router.html';
        },

        /**
         * 从服务端重新获取角色专属会话列表。
         * 由父组件在 WebSocket 事件（###START###）时通过 ref 调用。
         * characterId 为 null 时跳过。
         */
        refresh: async function () {
            if (!this.characterId) {
                this.sessions = [];
                return;
            }
            try {
                let result = await API.character.getSessions(this.characterId);
                if (result.status === 200) {
                    this.sessions = result.data || [];
                }
            } catch (error) {
                console.error('获取角色会话列表失败:', error);
            }
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

        /**
         * 按 id 查找会话对象。
         * @param {string} id
         * @returns {object|undefined} 会话对象引用
         */
        getSessionById: function (id) {
            return this.sessions.find(function (s) { return s.id === id; });
        },

        /* ---- 内部方法 ---- */

        /** 会话累计 token（口径见 utils/token-format.js，与主页侧边栏一致） */
        sessionTokenTotal: function (session) {
            return TokenFormat.total(session);
        },

        /** token 数字压缩：1234 → 1.2k */
        formatTokens: function (n) {
            return TokenFormat.short(n);
        },

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
