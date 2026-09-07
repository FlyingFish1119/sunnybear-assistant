/**
 * Character DB Panel — 角色数据库表查看面板
 *
 * 浮动在消息区右侧，展示当前角色的 SQLite 数据库表及全部数据。
 * 每次展开时重新加载数据，不缓存。
 *
 * Props:
 *   visible   — Boolean               是否展开
 *   characterId — String              当前角色 ID
 *   mainColor — String                主题色
 *
 * Emits:
 *   toggle   — 用户点击关闭时触发
 */
const CharacterDbPanel = {
    name: 'CharacterDbPanel',

    template: `
    <div class="character-db-panel" :class="{ collapsed: !visible }">
        <div class="character-db-header" :style="{ borderBottomColor: mainColor }">
            <div class="character-db-header-title">
                <i data-lucide="database" style="width: 16px; height: 16px;"></i>
                <span>数据库表</span>
                <span v-if="tables.length > 0" class="character-db-badge">{{ tables.length }}</span>
            </div>
            <button class="character-db-close-btn" @click="$emit('toggle')" title="关闭面板">
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
    <div class="character-db-overlay" :class="{ visible: visible && isMobile }" @click="$emit('toggle')"></div>
    `,

    props: {
        visible: { type: Boolean, default: false },
        characterId: { type: String, default: null },
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['toggle'],

    data: function () {
        return {
            tables: [],
            expandedId: null,
            loading: false,
            error: null,
            isMobile: window.innerWidth < 769,
            _lastFetched: null
        };
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
    },

    beforeUnmount: function () {
        if (this._onResize) {
            window.removeEventListener('resize', this._onResize);
        }
    },

    methods: {
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
            var self = this;
            this.$nextTick(function () {
                if (typeof lucide !== 'undefined') lucide.createIcons();
            });
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            var self = this;
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
