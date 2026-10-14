/**
 * 语音朗读开关（发送区插件）
 *
 * 定位：发送框上的 🔊 开关，决定本条消息是否要模型回朗读音频。开关状态与队列都在
 *       SendArea 里（data.ttsEnabled + localStorage 记忆 + ttsQueue），本组件只是它的一张脸：
 *       经 inject 拿到的 sendArea 实例读写状态、调 toggleTts()，不另存一份。
 *
 * 加载时自注册到发送区工具栏槽（见文件末尾）：order -20，站工具行第一位。
 * 用负数权重是为了排在插件按钮（如世界页的私聊/移交，order 0 / 10）之前，
 * 同 topbar-right 里 knowledge-flash 取 -10 的做法。
 *
 * 两个图标同时挂在 DOM 上、用 v-show 切换：lucide-safe 要求宿主元素常驻，
 * 用 v-if 会令 vnode.el 失效（见 utils/lucide-safe.js 顶部说明）。
 *
 * 样式见同目录 tts-toggle-button.css（开启态配色），基础按钮样式继承 send-area.css。
 *
 * Injects:
 *   sendArea — 发送区实例（可选）；读写 ttsEnabled、调用 toggleTts
 */
const TtsToggleButton = {
    name: 'TtsToggleButton',

    template: `
    <button class="send-area-icon-btn tts-toggle-button"
            :class="{ 'tts-on': ttsEnabled }"
            @click="toggle()"
            :title="ttsEnabled ? '本条消息语音朗读已开启（点击关闭）' : '开启本条消息的语音朗读'">
        <i v-show="ttsEnabled" data-lucide="volume-2"></i>
        <i v-show="!ttsEnabled" data-lucide="volume-x"></i>
    </button>`,

    inject: {
        // 可选：没有发送区实例时只读展示，点不动
        sendArea: { default: null }
    },

    computed: {
        // 开关状态住在 SendArea 里；经 provide 的实例读，依旧是响应式的
        ttsEnabled: function () {
            return this.sendArea ? !!this.sendArea.ttsEnabled : false;
        }
    },

    methods: {
        toggle: function () {
            if (this.sendArea) this.sendArea.toggleTts();
        }
    }
};

// 自注册进发送区工具栏槽（registry 定义于 send-area.js，须先于本文件加载）
if (typeof SendAreaPlugins !== 'undefined') {
    SendAreaPlugins.registerSlot('toolbar', TtsToggleButton, -20);
}
