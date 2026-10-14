/**
 * 上传文件按钮（发送区插件）
 *
 * 定位：发送框上的 📎 按钮，唤起文件选择器。文件本身的状态、chips、拖拽与落盘都由
 *       SendArea 内部的 file-upload 子组件负责，本组件只经 inject 的 sendArea 调它的
 *       公开入口 openFilePicker()，不自己持有任何文件状态。
 *
 * 加载时自注册到发送区工具栏槽（见文件末尾）：order -10，排在语音朗读开关之后、
 * 插件按钮之前。
 *
 * 基础按钮样式继承 send-area.css 的 .send-area-icon-btn，无需单独样式文件。
 *
 * Injects:
 *   sendArea — 发送区实例（可选）；调用 openFilePicker
 */
const UploadButton = {
    name: 'UploadButton',

    template: `
    <button class="send-area-icon-btn" @click="openFilePicker()" title="上传文件">
        <i data-lucide="paperclip"></i>
    </button>`,

    inject: {
        // 可选：没有发送区实例（拿不到文件选择器）时点了没反应
        sendArea: { default: null }
    },

    methods: {
        openFilePicker: function () {
            if (this.sendArea) this.sendArea.openFilePicker();
        }
    }
};

// 自注册进发送区工具栏槽（registry 定义于 send-area.js，须先于本文件加载）
if (typeof SendAreaPlugins !== 'undefined') {
    SendAreaPlugins.registerSlot('toolbar', UploadButton, -10);
}
