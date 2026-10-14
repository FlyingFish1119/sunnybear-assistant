/**
 * 下一次新对话的模型档位（发送区插件）
 *
 * 定位：一个三态按钮，循环「不干预 → 强制普通 → 强制高级」，决定下一个新建会话用哪个
 *       档位的模型。只作用于新建（create）请求，且请求一经发出即复位。
 *
 * 加载时自注册到发送区工具栏槽（见文件末尾）：order 0，排在语音开关（-20）与上传（-10）
 * 之后、步骤清单之前。未加载本文件的页面（如角色页 / 世界页，它们的后端 provider 明确禁用
 * 高级模型）不会渲染本组件，页面侧无需任何开关。
 *
 * 档位状态由 sessionStore 持有（nextProForce / cycleNextProForce），本组件只负责展示与点击：
 * 「用完即复位」必须落在 store 里，因为只有它知道 create 请求什么时候真正发出去。
 *
 * 三个图标同时挂在 DOM 上、用 v-show 切换：lucide-safe 要求宿主元素常驻，
 * 用 v-if 会令 vnode.el 失效（见 utils/lucide-safe.js 顶部说明）。
 *
 * 样式见同目录 next-pro-button.css。
 *
 * Injects:
 *   sessionStore — 会话仓库（可选）；读 isNewSession / nextProForce，调 cycleNextProForce
 */
const NextProButton = {
    name: 'NextProButton',

    template: `
    <button class="send-area-icon-btn next-pro-button"
            :class="{ 'is-locked': proForce !== null, 'is-pro': proForce === true }"
            :disabled="!isNewSession"
            @click="cycleNextProForce()"
            :title="proForceTitle">
        <i v-show="proForce === null" data-lucide="zap"></i>
        <i v-show="proForce === false" data-lucide="rabbit"></i>
        <i v-show="proForce === true" data-lucide="zap"></i>
    </button>`,

    inject: {
        // 可选：没有会话仓库（档位无处存放）时按钮不可用
        sessionStore: { default: null }
    },

    computed: {
        // 是否处于「新对话」状态：档位只对新建会话生效
        isNewSession: function () {
            return this.sessionStore ? this.sessionStore.isNewSession : false;
        },
        // null（不干预）/ false（强制普通）/ true（强制高级）
        proForce: function () {
            return this.sessionStore ? this.sessionStore.nextProForce : null;
        },
        proForceTitle: function () {
            if (!this.isNewSession) return '模型档位只对新建对话生效（当前是已有会话）';
            if (this.proForce === true) return '下一个新对话：强制使用高级模型（点击改回自动判断）';
            if (this.proForce === false) return '下一个新对话：强制使用普通模型（点击改为强制高级）';
            return '下一个新对话：自动判断模型（点击改为强制普通）';
        }
    },

    methods: {
        /** 循环切换下一次新建会话的模型档位（不干预 → 强制普通 → 强制高级 → 不干预） */
        cycleNextProForce: function () {
            if (this.sessionStore) this.sessionStore.cycleNextProForce();
        }
    }
};

// 自注册进发送区工具栏槽（registry 定义于 send-area.js，须先于本文件加载）
if (typeof SendAreaPlugins !== 'undefined') {
    SendAreaPlugins.registerSlot('toolbar', NextProButton, 0);
}
