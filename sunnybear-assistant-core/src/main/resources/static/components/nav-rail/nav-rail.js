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
 * 当前阶段：只搭骨架。按钮清单 items 是组件内部的占位数据，
 * 真正的功能（跳转 / 开关 / 弹窗）后续在 onSelect 里按 key 分发，
 * 或把 items 提成 props 由外面注入——等需求明确了再改，先不提前设计。
 *
 * 图标：沿用项目的 lucide 约定（data-lucide 由 lucide 替换成 svg），
 * 所以 mounted 里自己补一次 createIcons，不依赖父级的 updated 时机。
 */
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

    data() {
        return {
            // 按钮清单：目前只有「会话文件」和「命令」接了真实面板
            items: [
                { key: 'files', icon: 'folder-open',    label: '会话文件' },
                { key: 'shell', icon: 'chevrons-right', label: '命令' }
            ]
        };
    },

    methods: {
        onSelect(item) {
            // 高亮不在这里决定：只上报点击，由父级按面板的真实开合状态回灌
            this.$emit('select', item.key);
        }
    },

    mounted() {
        if (window.lucide) window.lucide.createIcons();
    }
};
