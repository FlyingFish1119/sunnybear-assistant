/**
 * 顶部信息栏组件
 *
 * 只负责「骨架 + 侧边栏开关」：菜单按钮内联，其余元素（模型名 / 会话名 /
 * 上下文用量环 / 工具确认 / 结构化提问 / 知识库闪现 / 连接状态）全部为槽组件，
 * 经 TopbarPlugins 注册表按锚点渲染（左栏 / 右栏）。各槽组件通过 inject('appSettings')
 * 自行取主题色 / 设置，通过 inject('sessionStore') 取会话数据，无需任何 prop。
 *
 * 页面可对内置元素用同名 key 覆盖或 removeSlot 移除，不再需要 hideBuiltin / isHidden。
 *
 * 插件扩展：通过全局注册表 TopbarPlugins 按锚点插入扩展组件，见文件顶部说明。
 *
 * 菜单按钮通过 WsBus 本地事件通知 chat-sidebar 切换：点击时 emit 'sidebar:toggle'。
 *
 * 依赖注入（可选）：
 *   wsBus                  — WebSocket 消息总线
 */

/**
 * 顶栏插件注册表（全局单例）。
 *
 * 插件在任意时机调用 registerSlot 声明扩展组件，顶栏挂载时同步进内部槽并按锚点渲染。
 * 锚点：
 *   'topbar-left'   左栏（菜单按钮之后）
 *   'topbar-right'  右栏
 *
 * 顶栏内置元素（模型名 / 会话名 / 上下文用量环 / 工具确认 / 结构化提问 /
 * 知识库闪现 / 连接状态）也一律以槽组件形式默认注册（见本文件底部）。
 * 页面可用同名 key 覆盖（registerSlot）或移除（removeSlot），无需 hideBuiltin。
 *
 * 内置 key 与默认锚点/顺序：
 *   'model'            topbar-left  order -30
 *   'session-name'     topbar-left  order  10
 *   'ctx-gauge'        topbar-left  order  20
 *   'pending-tool'     topbar-left  order  30
 *   'pending-question' topbar-left  order  40
 *   'knowledge-flash'  topbar-right order -10
 *   'connection'       topbar-right order  -5
 * （插件页普通插件一律 order 0，落在 model 之后 / session-name 之前，与旧布局一致）
 */
const TopbarPlugins = (function () {
    const slotsByAnchor = Object.create(null);

    return {
        /**
         * 注册一个顶栏扩展组件。传 key 时按 key 去重：已存在同名槽则就地覆盖
         * （用于替换核心内置元素）。槽组件自身 inject appSettings / sessionStore 取数据。
         */
        registerSlot: function (anchor, component, order, key) {
            if (!anchor || !component) return;
            if (!slotsByAnchor[anchor]) slotsByAnchor[anchor] = [];
            const arr = slotsByAnchor[anchor];
            const entry = { component: component, order: order || 0, key: key || null };
            if (key) {
                for (let i = 0; i < arr.length; i++) {
                    if (arr[i].key === key) {
                        arr[i] = entry;
                        arr.sort((a, b) => a.order - b.order);
                        return;
                    }
                }
            }
            arr.push(entry);
            arr.sort((a, b) => a.order - b.order);
        },
        /** 移除锚点上 key 对应的槽条目（页面去掉某内置元素时用） */
        removeSlot: function (anchor, key) {
            if (!anchor || !key || !slotsByAnchor[anchor]) return;
            slotsByAnchor[anchor] = slotsByAnchor[anchor].filter(function (s) {
                return s.key !== key;
            });
        },
        /** 供组件挂载时取某锚点已登记槽的拷贝 */
        snapshot: function (anchor) {
            return (slotsByAnchor[anchor] || []).slice();
        }
    };
})();

// 核心内置顶栏元素：以槽组件形式默认注册。页面用同名 key 覆盖或 removeSlot 去除。
if (typeof TopbarModel !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-left', TopbarModel, -30, 'model');
}
if (typeof ChatSessionName !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-left', ChatSessionName, 10, 'session-name');
}
if (typeof CtxGauge !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-left', CtxGauge, 20, 'ctx-gauge');
}
if (typeof ToolConfirm !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-left', ToolConfirm, 30, 'pending-tool');
}
if (typeof ToolQuestion !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-left', ToolQuestion, 40, 'pending-question');
}
if (typeof KnowledgeFlash !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-right', KnowledgeFlash, -10, 'knowledge-flash');
}
if (typeof ChatConnection !== 'undefined') {
    TopbarPlugins.registerSlot('topbar-right', ChatConnection, -5, 'connection');
}

const MessageTopbar = {
    name: 'MessageTopbar',

    template: `
    <div class="message-area-top">
        <div style="display: flex; align-items: center; gap: 6px; min-width: 0;">
            <button class="sidebar-toggle-btn" @click="toggleSidebar" title="展开/收起侧边栏">
                <i data-lucide="menu" style="width: 18px; height: 18px;"></i>
            </button>
            <!-- 左栏槽（内置：模型名 / 会话名 / 用量环 / 工具确认 / 提问；+ 插件） -->
            <component v-for="(slot, si) in leftSlots"
                       :key="'topbar-left-' + si"
                       :is="slot.component"></component>
        </div>
        <div style="display: flex; align-items: center; gap: 16px;">
            <!-- 右栏槽（内置：知识库闪现 / 连接状态；+ 插件） -->
            <component v-for="(slot, si) in rightSlots"
                       :key="'topbar-right-' + si"
                       :is="slot.component"></component>
        </div>
    </div>`,

    inject: {
        // 可选注入：未提供时降级
        wsBus: { default: null }
    },

    data: function () {
        return {
            // 锚点槽（挂载时从全局注册表同步，含内置元素 + 插件）
            leftSlots: TopbarPlugins.snapshot('topbar-left'),
            rightSlots: TopbarPlugins.snapshot('topbar-right')
        };
    },

    methods: {
        /** 展开/收起侧边栏：通过本地事件通知 chat-sidebar */
        toggleSidebar: function () {
            if (this.wsBus) {
                this.wsBus.emit('sidebar:toggle');
            }
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
