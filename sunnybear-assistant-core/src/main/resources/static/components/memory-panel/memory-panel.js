/**
 * 记忆面板 —— 双层抽屉（分组列表 + 组内条目）
 *
 * 与会话文件面板同一套门户：导航轨「记忆」按钮 → 第一层抽屉（分组列表，
 * 贴左侧滑出）→ 点分组 → 第二层抽屉（该组条目，接着往右展开）。
 *
 * 交互（保存全凭手动，新增过程不被打断）：
 *   点条目就地展开编辑区，不弹窗不跳页；Ctrl/Cmd+S 或点保存按钮落盘，
 *   打字期间绝不自动落库；切组 / 收起 / 切走时若仍有未保存内容会先落一次，防止白写
 *   删除走项目自绘 confirm-dialog（danger）；分组改名走它带输入框的形态
 *   Esc 依次往回关：先收编辑 → 再关第二层 → 最后关整个面板
 *
 * 分组是「虚拟」的：没有独立分组表，组列表由条目 group_name 聚合而来 ——
 * 给条目填一个新组名等于新组诞生，删光组内条目组自然散掉；
 * 分组改名由后端一条 SQL 批量执行（删组不做，一次灭一族太吓人）。
 *
 * 数据流：全量 list（按 create_time 倒序）一次拉完，前端聚合成组 ——
 * 组的先后 = 组内最新记忆的先后，组内保持倒序，点组零请求。
 *
 * Props:
 *   mainColor — String  主题色（树里图标与选中底色取它）
 *
 * 公开方法（通过 ref 调用，与 session-files / shell-panel 同一套约定）：
 *   toggle()   — 开 / 关整个面板
 *   open()     — 打开并加载
 *   closeAll() — 关闭面板（含第二层）
 *   close()    — 对外统一叫 close，导航轨切换时统一调这个
 */
const MemoryPanel = {
    name: 'MemoryPanel',

    template: `
    <div v-if="visible" class="mp-overlay" @click.self="closeAll">
        <!-- 第一层：分组列表 -->
        <aside class="mp-drawer mp-drawer--groups">
            <div class="mp-head">
                <div class="mp-title">
                    <i data-lucide="brain"></i>
                    <span class="mp-title-text">记忆</span>
                </div>
                <button class="mp-btn" title="新增分组" @click="newGroup">
                    <i data-lucide="folder-plus"></i>
                </button>
                <button class="mp-btn" title="刷新" @click="refreshAll()">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="mp-btn" title="关闭" @click="closeAll">
                    <i data-lucide="x"></i>
                </button>
            </div>
            <div class="mp-list">
                <div v-if="rootLoading" class="mp-hint">加载中…</div>
                <div v-else-if="groups.length === 0" class="mp-empty">
                    还没有分组<br>点右上角「新增分组」建第一个
                </div>
                <template v-else>
                    <div v-for="g in groups"
                         :key="g.name"
                         class="mp-group-row"
                         :class="{ 'is-active': viewerOpen && currentGroup === g.name }"
                         @click="openGroup(g.name)">
                        <span class="mp-row-icon"><i :data-lucide="viewerOpen && currentGroup === g.name ? 'folder-open' : 'folder'"></i></span>
                        <span class="mp-row-name">{{ g.name }}</span>
                        <span class="mp-row-count">{{ g.items.length }}</span>
                        <span class="mp-row-actions" @click.stop>
                            <button class="mp-row-btn" title="重命名" @click.stop="renameGroup(g)">
                                <i data-lucide="pencil-line"></i>
                            </button>
                        </span>
                    </div>
                </template>
            </div>
        </aside>

        <!-- 第二层：组内条目 -->
        <aside v-if="viewerOpen" class="mp-drawer mp-drawer--entries">
            <div class="mp-head">
                <div class="mp-title">
                    <i data-lucide="layers"></i>
                    <span class="mp-title-text">{{ currentGroup }}</span>
                </div>
                <button class="mp-btn" title="添加条目" @click="addEntry">
                    <i data-lucide="plus"></i>
                </button>
                <button class="mp-btn" title="刷新" @click="refreshAll()">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="mp-btn" title="关闭" @click="closeViewer">
                    <i data-lucide="x"></i>
                </button>
            </div>

            <div class="mp-entries">
                <div v-for="item in currentItems"
                     :key="item.__draft ? '__draft__' : item.id"
                     class="mp-entry"
                     :class="{ 'is-editing': isEditing(item) }">
                    <!-- 收起态：一行内容头 + 时间 + 删除 -->
                    <div v-if="!isEditing(item)" class="mp-entry-row" @click="startEdit(item)">
                        <span class="mp-entry-text">{{ item.content }}</span>
                        <span class="mp-entry-time">{{ formatTime(item.createTime) }}</span>
                        <span class="mp-entry-actions" @click.stop>
                            <button class="mp-row-btn is-danger" title="删除" @click.stop="deleteEntry(item)">
                                <i data-lucide="trash-2"></i>
                            </button>
                        </span>
                    </div>
                    <!-- 展开态：就地编辑（分组 + 内容），无第三层无弹窗；保存只认 Ctrl+S 与保存按钮 -->
                    <div v-else class="mp-entry-edit">
                        <div class="mp-edit-bar">
                            <el-select class="mp-group-select"
                                       v-model="editForm.groupName"
                                       filterable
                                       allow-create
                                       default-first-option
                                       placeholder="选择或输入新分组"
                                       @change="markDirty">
                                <el-option v-for="g in groups"
                                           :key="g.name"
                                           :label="g.name"
                                           :value="g.name"></el-option>
                            </el-select>
                            <span class="mp-edit-spacer"></span>
                            <span class="mp-edit-state">
                                <span class="mp-status-dot" :class="statusClass"></span>
                                <span>{{ statusText }}</span>
                            </span>
                            <button class="mp-row-btn" title="保存（Ctrl+S）" :disabled="!dirty" @click="saveNow">
                                <i data-lucide="save"></i>
                            </button>
                            <button class="mp-row-btn" title="收起" @click="collapseEdit">
                                <i data-lucide="chevron-up"></i>
                            </button>
                        </div>
                        <textarea class="mp-textarea"
                                  v-model="editForm.content"
                                  rows="4"
                                  placeholder="记忆内容，一句话一个事实"
                                  @input="markDirty"></textarea>
                        <div v-if="saveFailed" class="mp-edit-error">保存失败：{{ saveErrorMsg }}</div>
                    </div>
                </div>
                <div v-if="currentItems.length === 0" class="mp-empty">
                    这个组还没有记忆<br>点上方「+」写一条
                </div>
            </div>

            <div class="mp-status">
                <span class="mp-status-path" :title="currentGroup">{{ currentGroup }}</span>
                <span class="mp-status-count">{{ realItemCount }} 条</span>
            </div>
        </aside>
    </div>

    <!-- 删除 / 改名确认：与会话删除、知识删除同一套视觉 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    data() {
        return {
            visible: false,

            /* 全量条目（倒序）与前端聚合出的组列表 */
            allItems: [],
            groups: [],
            rootLoading: false,

            /* 第二层：当前打开的组 */
            viewerOpen: false,
            currentGroup: '',

            /* 就地编辑：editingKey 为空 = 全收起；'__draft__' = 新条目草稿；数字 = 条目 id */
            editingKey: '',
            editForm: { content: '', groupName: '' },
            dirty: false,
            saving: false,
            saveFailed: false,
            saveErrorMsg: '',
            savedTip: ''
        };
    },

    computed: {
        /** 当前组的条目（倒序）；草稿编辑中时在末尾挂一条临时行 */
        currentItems() {
            const g = this.groups.find((x) => x.name === this.currentGroup);
            const items = g ? g.items : [];
            if (this.editingKey === '__draft__') {
                return items.concat([{ __draft: true, id: null, content: '', createTime: '' }]);
            }
            return items;
        },

        realItemCount() {
            const g = this.groups.find((x) => x.name === this.currentGroup);
            return g ? g.items.length : 0;
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
            if (this.dirty) return '未保存（Ctrl+S 保存）';
            return this.savedTip ? '已保存 ' + this.savedTip : '已保存';
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
            await this.refreshAll(true);
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
            this.viewerOpen = false;
            this.currentGroup = '';
            this.editingKey = '';
            this.dirty = false;
            this.saveFailed = false;
            this.saveErrorMsg = '';
            this.savedTip = '';
        },

        /* ==================== 数据 ==================== */

        /**
         * 拉全量列表并重新聚合组。silent=true 时不弹错（面板未开时的预加载）。
         * 当前组已被删光时顺手收起第二层，不留一个空壳。
         */
        async refreshAll(silent) {
            if (this.visible) this.rootLoading = true;
            try {
                const res = await API.memory.list();
                if (res.status === 200) {
                    this.allItems = res.data || [];
                    this.rebuildGroups();
                    if (this.viewerOpen && !this.groups.some((g) => g.name === this.currentGroup)) {
                        this.closeViewer();
                    }
                } else if (!silent && window.SbToast) {
                    window.SbToast.error(res.message || '拉取记忆失败');
                }
            } catch (e) {
                if (!silent && window.SbToast) window.SbToast.error('拉取记忆失败: ' + e.message);
            } finally {
                this.rootLoading = false;
            }
        },

        /** 倒序全量 → 组列表。组的先后 = 组内最新记忆的先后（Map 保插入序）。 */
        rebuildGroups() {
            const map = new Map();
            for (const item of this.allItems) {
                const name = (item.groupName && item.groupName.trim()) || '未分类';
                if (!map.has(name)) map.set(name, { name: name, items: [] });
                map.get(name).items.push(item);
            }
            this.groups = Array.from(map.values());
        },

        /* ==================== 分组 ==================== */

        openGroup(name) {
            // 切组前把没保存的先落盘，别让上一组的输入白写
            if (this.editingKey && this.dirty) this.saveNow();
            this.editingKey = '';
            this.currentGroup = name;
            this.viewerOpen = true;
            this.savedTip = '';
            this.saveFailed = false;
            this.scheduleIcons();
        },

        /** 第一层「新增分组」：输入组名建组并直接打开写第一条（虚拟组：不写就散，不留空壳） */
        async newGroup() {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '新增分组',
                    message: '输入新分组的名字，创建后直接打开它写第一条记忆',
                    confirmText: '创建',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: '',
                    inputPlaceholder: '例如：我是谁'
                });
            } catch (e) {
                return;   // 取消
            }
            input = String(input || '').trim();
            if (!input) {
                if (window.SbToast) window.SbToast.warning('分组名不能为空');
                return;
            }
            if (this.groups.some((g) => g.name === input)) {
                this.openGroup(input);   // 组已存在：直接打开，别起两条同名
                return;
            }
            this.currentGroup = input;
            this.viewerOpen = true;
            this.addEntry();   // 草稿组名 = 新组名，写完落库组才算真身
            this.scheduleIcons();
        },

        async renameGroup(g) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            let input;
            try {
                input = await dialog.show({
                    title: '重命名分组',
                    message: '组下所有记忆会一起改名',
                    confirmText: '确定',
                    cancelText: '取消',
                    type: 'info',
                    inputValue: g.name,
                    inputPlaceholder: '新的分组名'
                });
            } catch (e) {
                return;   // 取消
            }
            input = String(input || '').trim();
            if (!input || input === g.name) return;
            try {
                const res = await API.memory.groupRename(g.name, input);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success(res.data || '已重命名');
                    if (this.currentGroup === g.name) this.currentGroup = input;
                    if (this.editForm.groupName === g.name) this.editForm.groupName = input;
                    await this.refreshAll();
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '重命名失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('重命名失败: ' + e.message);
            }
        },

        /* ==================== 条目编辑（就地展开） ==================== */

        itemKey(item) {
            return item.__draft ? '__draft__' : item.id;
        },

        isEditing(item) {
            return this.editingKey !== '' && this.editingKey === this.itemKey(item);
        },

        startEdit(item) {
            if (this.isEditing(item)) return;
            // 从别的编辑位切过来：先把没保存的落盘
            if (this.editingKey && this.dirty) this.saveNow();
            this.editingKey = this.itemKey(item);
            this.editForm = {
                content: item.content || '',
                groupName: (item.groupName && item.groupName.trim()) || this.currentGroup || '未分类'
            };
            this.dirty = false;
            this.saveFailed = false;
            this.saveErrorMsg = '';
            this.savedTip = '';
        },

        addEntry() {
            if (this.editingKey === '__draft__') return;   // 草稿已经在了
            if (this.editingKey && this.dirty) this.saveNow();
            this.editingKey = '__draft__';
            this.editForm = { content: '', groupName: this.currentGroup || '未分类' };
            this.dirty = false;
            this.saveFailed = false;
            this.saveErrorMsg = '';
            this.savedTip = '';
            this.scheduleIcons();
        },

        collapseEdit() {
            // 收起前把没保存的先落盘，别让输入白写
            if (this.dirty) this.saveNow();
            this.editingKey = '';
        },

        /** 只标记脏位，不自动落盘 —— 保存只认 Ctrl+S 和保存按钮（自动保存会让新增过程显得很乱） */
        markDirty() {
            this.dirty = true;
            this.saveFailed = false;
        },

        /** 立即保存当前编辑位；新条目拿到 id 后转正，后续编辑就是 update */
        async saveNow() {
            if (!this.editingKey || !this.dirty || this.saving) return;

            const content = (this.editForm.content || '').trim();
            if (!content) {
                if (this.editingKey === '__draft__') {
                    // 空草稿：丢弃，不留幽灵行
                    this.editingKey = '';
                    this.dirty = false;
                    return;
                }
                this.saveFailed = true;
                this.saveErrorMsg = '内容不能为空，补上再保存';
                return;
            }
            const groupName = (this.editForm.groupName || '').trim() || '未分类';
            const isDraft = this.editingKey === '__draft__';
            const body = {
                content: content,
                groupName: groupName,
                mode: isDraft ? 'add' : 'update'
            };
            if (!isDraft) body.id = this.editingKey;

            this.saving = true;
            try {
                const res = await API.memory.save(body);
                if (res.status === 200) {
                    this.saveFailed = false;
                    this.saveErrorMsg = '';
                    this.savedTip = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                    // 新条目转正：编辑位从草稿切到真实 id
                    if (isDraft && res.data && res.data.id != null) {
                        this.editingKey = res.data.id;
                    }
                    this.dirty = false;
                    // 服务端才是排序与分组的权威，保存后整表重聚合；
                    // editForm 是独立草稿，不受 groups 重建影响，输入不会被打断
                    await this.refreshAll(true);
                } else {
                    this.saveFailed = true;
                    this.saveErrorMsg = res.message || '保存失败';
                    if (window.SbToast) window.SbToast.error(res.message || '保存失败');
                }
            } catch (e) {
                this.saveFailed = true;
                this.saveErrorMsg = e.message || '保存失败';
                if (window.SbToast) window.SbToast.error('保存失败: ' + e.message);
            } finally {
                this.saving = false;
            }
        },

        async deleteEntry(item) {
            if (item.__draft) return;
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            try {
                await dialog.show({
                    title: '删除记忆',
                    message: '确定要删除这条记忆吗？此操作不可恢复。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return;   // 取消
            }
            try {
                const res = await API.memory.delete(item.id);
                if (res.status === 200) {
                    if (window.SbToast) window.SbToast.success('已删除');
                    if (this.editingKey === String(item.id) || this.editingKey === item.id) {
                        this.editingKey = '';
                        this.dirty = false;
                    }
                    await this.refreshAll();
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '删除失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        /* ==================== 展示辅助 / 快捷键 ==================== */

        formatTime(time) {
            return time || '';
        },

        onKeydown(e) {
            if (!this.visible) return;
            // Ctrl/Cmd+S：编辑位开着就地保存并吃掉浏览器的保存页面快捷键
            if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
                if (this.editingKey) {
                    e.preventDefault();
                    this.saveNow();
                }
                return;
            }
            if (e.key !== 'Escape') return;
            if (this.editingKey) {
                this.collapseEdit();
            } else if (this.viewerOpen) {
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

    watch: {
        /* 开关状态上报父级：导航轨靠它把高亮对准真正打开的面板 */
        visible(val) {
            this.$emit('visible-change', val);
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
