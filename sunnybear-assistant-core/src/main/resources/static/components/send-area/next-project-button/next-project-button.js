/**
 * 下一次新对话的项目归属（发送区插件）
 *
 * 定位：一个图标按钮，点开浮层挑项目 ——
 *       不指定（默认）：照常交给 Jev 按消息内容判定这条消息属于哪个项目；
 *       选定了：下一个新建会话强制归入该项目，服务端见新会话已自带 chat_project_id
 *       就直接跳过 Jev 判定（见 CreateChatMessageRequestHandler#judgeProjectTask）。
 *       思路与旁边那个模型档位按钮一致：主人的显式指定优先于自动判定。
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
 * 只靠显示态区分：未指定是灰底「文件夹」，已指定撑成胶囊显示项目名并染主色。
 *
 * 样式见同目录 next-project-button.css。
 *
 * Injects:
 *   sessionStore — 会话仓库（可选）；读 isNewSession / nextProjectId / projectList / projectMap，
 *                  调 setNextProject
 */
const NextProjectButton = {
    name: 'NextProjectButton',

    template: `
    <div class="next-project-button-wrap">
        <button class="send-area-icon-btn next-project-button"
                :class="{ 'is-locked': !!projectId }"
                :disabled="!isNewSession"
                @click.stop="togglePanel"
                :title="buttonTitle">
            <i :data-lucide="projectId ? 'folder-open' : 'folder'"></i>
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
                    <div v-for="project in filteredProjects" :key="project.id"
                         class="next-project-item"
                         :class="{ 'is-current': project.id === projectId }"
                         @click="choose(project.id)">
                        <span class="next-project-dot" :style="{ background: colorOf(project) }"></span>
                        <span class="next-project-name">{{ project.name }}</span>
                        <i v-show="project.id === projectId" data-lucide="check"
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
        // null（不指定，交给 Jev）/ 项目 id
        projectId: function () {
            return this.sessionStore ? this.sessionStore.nextProjectId : null;
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
        // 按钮上显示的项目名：字典里查不到（项目刚被删）时退回占位文案
        selectedName: function () {
            if (!this.projectId) return '';
            const map = (this.sessionStore && this.sessionStore.state
                && this.sessionStore.state.projectMap) || {};
            const project = map[this.projectId];
            return (project && project.name) || '未知项目';
        },
        buttonTitle: function () {
            if (!this.isNewSession) return '项目归属只对新建对话生效（当前是已有会话）';
            if (this.projectId) {
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
         */
        projectList: function (list) {
            if (!this.projectId || !list.length) return;
            if (!list.some(p => p.id === this.projectId)) {
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
