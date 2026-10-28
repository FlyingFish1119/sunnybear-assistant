/**
 * 下一次新对话的项目归属（发送区插件）
 *
 * 定位：一个图标按钮，点开浮层三选一 ——
 *       不指定（默认）：照常交给 Jev 按消息内容判定这条消息属于哪个项目；
 *       选定了：下一个新建会话强制归入该项目，服务端见新会话已自带 chat_project_id
 *       就直接跳过 Jev 判定（见 CreateChatMessageRequestHandler#judgeProjectTask）；
 *       强制不绑定：下一个新建会话必须是散会话 —— 请求里带 chat_project_id = -1（哨兵），
 *       服务端同样跳过 Jev 判定，并把这个键从会话 extension 上摘掉，绝不落库。
 *       三种状态与旁边那个模型档位按钮一致：主人的显式指定优先于自动判定。
 *
 * 只作用于新建（create）会话，已有会话时按钮置灰 —— 改一个已有会话的归属，
 * 走左侧会话列表的右键菜单（store.attachToProject）。选择结果存在
 * sessionStore.nextProjectId，请求一经发出即复位（见 sessionStore.sendMessage）。
 *
 * 加载时自注册到发送区工具栏槽（见文件末尾）：order 5，排在模型档位（0）之后、
 * 步骤清单之前。未加载本文件的页面（如角色页 / 世界页）不会渲染本组件，页面侧无需开关。
 *
 * 面板是动态挂上来的，里面的 lucide 图标靠 mounted / updated 各补一次 createIcons
 * （与 settings 页同一套做法，见 utils/lucide-safe.js）。按钮本身的图标不用 v-if 切换，
 * 只靠显示态区分：未指定是灰底「文件夹」，已指定撑成胶囊显示项目名并染主色，
 * 强制不绑定则撑成中性灰胶囊（folder-x），一眼能看出这条会话被锁成散会话了。
 *
 * 样式见同目录 next-project-button.css。
 *
 * Injects:
 *   sessionStore — 会话仓库（可选）；读 isNewSession / nextProjectId / projectList / projectMap，
 *                  调 setNextProject；哨兵值取 sessionStore.FORCE_UNBOUND_ID（兜底 '-1'）
 */
const NextProjectButton = {
    name: 'NextProjectButton',

    template: `
    <div class="next-project-button-wrap">
        <button class="send-area-icon-btn next-project-button"
                :class="{ 'is-locked': !!realProjectId, 'is-unbound': forceUnbound }"
                :disabled="!isNewSession"
                @click.stop="togglePanel"
                :title="buttonTitle">
            <i :data-lucide="forceUnbound ? 'folder-x' : (realProjectId ? 'folder-open' : 'folder')"></i>
            <span v-show="!!projectId" class="next-project-button-label">{{ selectedName }}</span>
        </button>
        <transition name="next-project-fade">
            <div v-if="panelOpen" class="next-project-backdrop" @click="closePanel"></div>
        </transition>
        <transition name="next-project-pop">
            <div v-if="panelOpen" class="next-project-panel" @click.stop>
                <div class="next-project-search">
                    <i data-lucide="search"></i>
                    <input ref="searchInput" type="text" v-model="keyword"
                           placeholder="搜索项目" @keydown.esc="closePanel">
                </div>
                <div class="next-project-list">
                    <div class="next-project-item"
                         :class="{ 'is-current': !projectId }"
                         @click="choose(null)">
                        <span class="next-project-dot next-project-dot--auto"></span>
                        <span class="next-project-name">不指定（自动判断）</span>
                        <i v-show="!projectId" data-lucide="check" class="next-project-check"></i>
                    </div>
                    <div class="next-project-item"
                         :class="{ 'is-current': forceUnbound }"
                         @click="chooseUnbound">
                        <span class="next-project-dot next-project-dot--unbound"></span>
                        <span class="next-project-name">强制不绑定（散会话）</span>
                        <i v-show="forceUnbound" data-lucide="check" class="next-project-check"></i>
                    </div>
                    <div v-for="project in filteredProjects" :key="project.id"
                         class="next-project-item"
                         :class="{ 'is-current': project.id === realProjectId }"
                         @click="choose(project.id)">
                        <span class="next-project-dot" :style="{ background: colorOf(project) }"></span>
                        <span class="next-project-name">{{ project.name }}</span>
                        <i v-show="project.id === realProjectId" data-lucide="check"
                           class="next-project-check"></i>
                    </div>
                    <div v-if="!filteredProjects.length" class="next-project-empty">
                        {{ projectList.length ? '没有匹配的项目' : '还没有项目' }}
                    </div>
                </div>
                <div class="next-project-hint">仅对下一个新建对话生效</div>
            </div>
        </transition>
    </div>`,

    inject: {
        // 可选：没有会话仓库（选择无处存放）时按钮不可用
        sessionStore: { default: null }
    },

    data: function () {
        return {
            panelOpen: false,
            keyword: ''
        };
    },

    computed: {
        // 是否处于「新对话」状态：归属指定只对新建会话生效
        isNewSession: function () {
            return this.sessionStore ? this.sessionStore.isNewSession : false;
        },
        // 原始选择：null（不指定，交给 Jev）/ 项目 id / '-1'（强制不绑定）
        projectId: function () {
            return this.sessionStore ? this.sessionStore.nextProjectId : null;
        },
        // 「强制不绑定」的哨兵值，与后端 ChatProjectBindings.FORCE_UNBOUND 对齐
        forceUnboundId: function () {
            return (this.sessionStore && this.sessionStore.FORCE_UNBOUND_ID) || '-1';
        },
        // 是否选了「强制不绑定」
        forceUnbound: function () {
            return !!this.projectId && this.projectId === this.forceUnboundId;
        },
        // 真正选中的项目 id：不指定 / 强制不绑定都是 null，项目色点与项目名都看它
        realProjectId: function () {
            return this.projectId && !this.forceUnbound ? this.projectId : null;
        },
        // 全部项目（最近改动倒序）
        projectList: function () {
            return this.sessionStore ? this.sessionStore.projectList : [];
        },
        // 搜索过滤：按项目名做不区分大小写的包含匹配
        filteredProjects: function () {
            const kw = (this.keyword || '').trim().toLowerCase();
            if (!kw) return this.projectList;
            return this.projectList.filter(p => String(p.name || '').toLowerCase().includes(kw));
        },
        // 按钮上显示的名字：强制不绑定给固定文案；项目查不到（刚被删）时退回占位
        selectedName: function () {
            if (this.forceUnbound) return '不绑定项目';
            if (!this.realProjectId) return '';
            const map = (this.sessionStore && this.sessionStore.state
                && this.sessionStore.state.projectMap) || {};
            const project = map[this.realProjectId];
            return (project && project.name) || '未知项目';
        },
        buttonTitle: function () {
            if (!this.isNewSession) return '项目归属只对新建对话生效（当前是已有会话）';
            if (this.forceUnbound) return '下一个新对话：强制不绑定项目（点击可改）';
            if (this.realProjectId) {
                return '下一个新对话：强制归入「' + this.selectedName + '」（点击可改）';
            }
            return '下一个新对话：项目归属自动判断（点击可指定）';
        }
    },

    watch: {
        /**
         * 项目被删后，已选中的 id 可能已经不在列表里：列表非空时清掉这个悬空选择，
         * 免得下一个新会话挂上一个不存在的项目。列表为空（还没加载 / 加载失败）时不动，
         * 避免刚建完项目还没刷到列表就把主人的选择擦掉。
         * 强制不绑定不指向任何真实项目，不参与这条检查 —— 否则项目一删就会把散会话的锁定擦掉。
         */
        projectList: function (list) {
            if (!this.realProjectId || !list.length) return;
            if (!list.some(p => p.id === this.realProjectId)) {
                this.setProject(null);
            }
        }
    },

    mounted: function () {
        this.renderIcons();
    },

    updated: function () {
        this.renderIcons();
    },

    methods: {
        /** 面板是动态挂上来的，lucide 需要在这里补渲染（只在组件自己的子树里找） */
        renderIcons: function () {
            if (typeof lucide !== 'undefined' && this.$el) {
                lucide.createIcons({ root: this.$el });
            }
        },

        /** 开 / 关选择面板；打开时清空上次的搜索词并聚焦输入框（可以直接敲字） */
        togglePanel: function () {
            if (!this.isNewSession) return;
            this.panelOpen = !this.panelOpen;
            if (this.panelOpen) {
                this.keyword = '';
                const self = this;
                this.$nextTick(function () {
                    if (self.$refs.searchInput) self.$refs.searchInput.focus();
                });
            }
        },

        closePanel: function () {
            this.panelOpen = false;
        },

        /** 选中一个项目（传 null = 退回自动判断）后收起面板 */
        choose: function (projectId) {
            this.setProject(projectId);
            this.closePanel();
        },

        /** 选「强制不绑定」：把哨兵值当作选择发出去，服务端据此跳过判定且不落键 */
        chooseUnbound: function () {
            this.setProject(this.forceUnboundId);
            this.closePanel();
        },

        setProject: function (projectId) {
            if (this.sessionStore) this.sessionStore.setNextProject(projectId || null);
        },

        /** 项目色：与左侧列表 / 项目面板同源（utils/project-color.js） */
        colorOf: function (project) {
            if (typeof ProjectColor === 'undefined') return 'transparent';
            return ProjectColor.of(project);
        }
    }
};

// 自注册进发送区工具栏槽（registry 定义于 send-area.js，须先于本文件加载）
if (typeof SendAreaPlugins !== 'undefined') {
    SendAreaPlugins.registerSlot('toolbar', NextProjectButton, 5);
}
