/**
 * 左侧导航轨组件（竖向图标按钮条）
 *
 * 放在 #app 最左侧、会话侧边栏之外：侧边栏管「会话列表」，这条轨管「全局入口」，
 * 两者各占一条，互不挤占。侧边栏折叠时它保持常驻——导航轨本来就是稳态结构。
 *
 * Props:
 *   mainColor — String  主题色（铺底 + 选中态图标取色）
 *
 * Emits:
 *   select(key) — 点击某个按钮，携带按钮的 key（父级接功能用）
 *
 * 按钮清单不写死在组件里，由 RailPlugins 注册表提供（注册方在 index.html 末尾）。
 *
 * 图标：沿用项目的 lucide 约定（data-lucide 由 lucide 替换成 svg），
 * 所以 mounted/updated 里自己补 createIcons，不依赖父级的 updated 时机。
 */

/**
 * 导航轨注册表（全局单例），配方照抄 TopbarPlugins（message-topbar.js）：
 * 注册一条同时接通「按钮 + 面板挂载 + 互斥开合」三层，新增面板只需一行注册。
 *
 * register(key, entry)：
 *   key             按钮 / 面板的唯一标识（也是高亮与互斥的 key）
 *   entry.icon      lucide 图标名
 *   entry.label     按钮文字（title / aria-label）
 *   entry.component 面板组件对象（可选）：有值则由主模板 v-for 动态挂载；
 *                   面板需实现 toggle()/close() 与 visible 状态、并发 visible-change 事件
 *   entry.order     排序权重，小的在前（默认 0）
 * 同 key 重复注册以后者为准。
 */
const RailPlugins = (function () {
    const entries = Object.create(null);
    const order = [];

    function register(key, entry) {
        if (!key || !entry) return;
        if (!(key in entries)) order.push(key);
        entries[key] = Object.assign({}, entry, { key: key });
    }

    return {
        register: register,
        /** 按 order 升序返回已注册项（nav-rail 取按钮、主模板取面板都走这） */
        snapshot: function () {
            return order
                .map(function (k) { return entries[k]; })
                .sort(function (a, b) { return (a.order || 0) - (b.order || 0); })
                .slice();
        }
    };
})();

const NavRail = {
    name: 'NavRail',

    template: `
    <nav class="nav-rail"
         :style="{ backgroundColor: mainColor, '--nav-rail-accent': mainColor }">
        <div class="nav-rail-list">
            <button v-for="item in items"
                    :key="item.key"
                    type="button"
                    class="nav-rail-btn"
                    :class="{ active: activeKey === item.key }"
                    :title="item.label"
                    :aria-label="item.label"
                    @click="onSelect(item)">
                <i :data-lucide="item.icon"></i>
            </button>
        </div>
    </nav>
    `,

    props: {
        mainColor: { type: String, default: '' },

        /**
         * 当前高亮的按钮 key —— 由父级受控传入。
         * 语义必须是「当前打开的面板」，而不是「最后点过的按钮」：
         * 后者会让命令面板开着、高亮却跑到别的按钮上，画面对不上。
         */
        activeKey: { type: String, default: '' }
    },

    emits: ['select'],

    computed: {
        /** 按钮清单来自 RailPlugins 注册表（注册方在 index.html 末尾），组件内不写死 */
        items() {
            return RailPlugins.snapshot();
        }
    },

    methods: {
        onSelect(item) {
            // 高亮不在这里决定：只上报点击，由父级按面板的真实开合状态回灌
            this.$emit('select', item.key);
        }
    },

    mounted() {
        if (window.lucide) window.lucide.createIcons();
    },

    updated() {
        // 运行时才注册的按钮（插件页同款时机）会是还没替换的 <i>，
        // 更新后补一次 createIcons，把新图标换成 svg
        this.$nextTick(function () {
            if (window.lucide) window.lucide.createIcons();
        });
    }
};
