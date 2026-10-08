/**
 * Character DB — 顶栏开关 + 右侧角色数据库面板（单组件，自包含）
 *
 * 以插件形态注册进顶栏右栏：
 *   TopbarPlugins.registerSlot('topbar-right', CharacterDb, 0)
 * 开关按钮渲染在顶栏槽内；面板经 Teleport 投放到 .message-area-wrapper 下
 * （position:fixed 浮动于右侧，不参与 flex 布局）。
 *
 * 展开态由组件内部持有，按钮高亮与面板开合天然同步，不再走 WsBus 事件流转，
 * 页面根组件也无需再维护 dbPanelVisible / 订阅 'character-db:toggle'。
 * （与 AgentLogSidebar 同一套路。）
 *
 * 每次展开时重新拉取数据库表，不缓存。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * Injects:
 *   characterPage — 页面级共享状态（读当前角色 id）
 *
 * 依赖全局：API、lucide。
 */
const CharacterDb = {
    name: 'CharacterDb',

    template: `
    <button class="sidebar-toggle-btn" @click="toggle"
            :title="visible ? '收起数据库面板' : '展开数据库面板'"
            :style="visible ? {color: mainColor} : {}">
        <i data-lucide="database" style="width: 18px; height: 18px;"></i>
    </button>
    <teleport v-if="anchorReady" to=".message-area-wrapper">
        <div class="character-db-panel" :class="{ collapsed: !visible }">
            <div class="character-db-header" :style="{ borderBottomColor: mainColor }">
                <div class="character-db-header-title">
                    <i data-lucide="database" style="width: 16px; height: 16px;"></i>
                    <span>数据库表</span>
                    <span v-if="tables.length > 0" class="character-db-badge">{{ tables.length }}</span>
                </div>
                <button class="character-db-close-btn" @click="toggle" title="关闭面板">
                    <i data-lucide="x" style="width: 16px; height: 16px;"></i>
                </button>
            </div>
            <div class="character-db-list" ref="dbList">
                <!-- 加载中 -->
                <div v-if="loading" class="character-db-empty">
                    <i data-lucide="loader-circle" class="spin-icon" style="width: 28px; height: 28px;"></i>
                    <span>加载中...</span>
                </div>
                <!-- 错误 -->
                <div v-else-if="error" class="character-db-empty">
                    <i data-lucide="alert-triangle" style="width: 28px; height: 28px; color: #ff4d4f;"></i>
                    <span>{{ error }}</span>
                    <button class="character-db-retry-btn" @click="fetchTables">重试</button>
                </div>
                <!-- 空状态 -->
                <div v-else-if="tables.length === 0" class="character-db-empty">
                    <i data-lucide="database-zap" style="width: 28px; height: 28px;"></i>
                    <span>暂无数据表</span>
                    <span class="character-db-empty-hint">AI 与角色互动时会自动创建和维护数据表</span>
                </div>
                <!-- 表列表 -->
                <div v-for="table in tables" :key="table.tableName" class="character-db-table"
                     :class="{ expanded: expandedId === table.tableName }">
                    <div class="character-db-table-header" @click="toggleTable(table)">
                        <i class="character-db-expand-icon"
                           :class="{ rotated: expandedId === table.tableName }"
                           data-lucide="chevron-down"
                           style="width: 14px; height: 14px;"></i>
                        <i data-lucide="table" style="width: 14px; height: 14px; color: var(--main-color, lightsalmon);"></i>
                        <span class="character-db-table-name">{{ table.tableName }}</span>
                        <span class="character-db-table-count">{{ table.rowCount }} 行</span>
                    </div>
                    <div v-if="expandedId === table.tableName" class="character-db-table-body">
                        <div v-if="table.error" class="character-db-table-error">
                            <i data-lucide="alert-circle" style="width: 14px; height: 14px; color: #ff4d4f;"></i>
                            <span>读取失败: {{ table.error }}</span>
                        </div>
                        <div v-else-if="table.rows.length === 0" class="character-db-table-empty">
                            <span>（表为空）</span>
                        </div>
                        <div v-else class="character-db-table-wrap">
                            <table class="character-db-data-table">
                                <thead>
                                    <tr>
                                        <th v-for="col in table.columns" :key="col.name">
                                            <div class="character-db-col-header">
                                                <span class="character-db-col-name">{{ col.name }}</span>
                                                <span class="character-db-col-type">{{ col.type }}</span>
                                                <span v-if="col.pk" class="character-db-col-badge pk">PK</span>
                                                <span v-if="col.notNull" class="character-db-col-badge nn">NN</span>
                                            </div>
                                        </th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr v-for="(row, ri) in table.rows" :key="ri">
                                        <td v-for="col in table.columns" :key="col.name"
                                            :class="{ 'null-cell': row[col.name] === null || row[col.name] === undefined }">
                                            {{ row[col.name] !== null && row[col.name] !== undefined ? row[col.name] : 'NULL' }}
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <div class="character-db-overlay" :class="{ visible: visible && isMobile }" @click="toggle"></div>
    </teleport>
    `,

    inject: {
        appSettings: { default: null },
        // 页面级共享状态：取当前角色 id
        characterPage: { default: null }
    },

    data: function () {
        return {
            visible: false,
            tables: [],
            expandedId: null,
            loading: false,
            error: null,
            isMobile: window.innerWidth < 769,
            // Teleport 目标就绪标记：面板要投放到 .message-area-wrapper，
            // 初始 patch 时该元素可能尚未入文档，等 mounted（post-flush）再开启
            anchorReady: false
        };
    },

    computed: {
        characterId: function () {
            return this.characterPage ? this.characterPage.characterId : null;
        },
        mainColor: function () {
            if (this.appSettings && this.appSettings.mainColor) return this.appSettings.mainColor;
            return 'lightsalmon';
        }
    },

    watch: {
        visible: function (val) {
            if (val && this.characterId) {
                this.fetchTables();
            }
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
    },

    beforeUnmount: function () {
        if (this._onResize) {
            window.removeEventListener('resize', this._onResize);
            this._onResize = null;
        }
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

        /* ---- 内部 ---- */

        fetchTables: function () {
            if (!this.characterId) return;
            this.loading = true;
            this.error = null;
            this.expandedId = null;
            var self = this;
            API.character.dbTables(this.characterId).then(function (result) {
                if (result.status === 200) {
                    self.tables = result.data || [];
                } else {
                    self.error = result.message || '获取数据库表失败';
                }
            }).catch(function (err) {
                console.error('获取数据库表失败:', err);
                self.error = '网络请求失败，请检查网络连接';
            }).finally(function () {
                self.loading = false;
                self.$nextTick(function () {
                    if (typeof lucide !== 'undefined') lucide.createIcons();
                });
            });
        },

        toggleTable: function (table) {
            this.expandedId = this.expandedId === table.tableName ? null : table.tableName;
            if (this.expandedId !== table.tableName) return;
            // 展开后刷新图标
            this.$nextTick(function () {
                if (typeof lucide !== 'undefined') lucide.createIcons();
            });
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
