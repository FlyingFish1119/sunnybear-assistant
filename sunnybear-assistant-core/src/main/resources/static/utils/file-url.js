/**
 * 文件 URL 工具函数。
 * 通过 app.config.globalProperties.$fileUrl 注册为全局方法，
 * 模板中直接 $fileUrl.proxy(...) 调用。
 */
const FileUrlUtils = {
    proxy: function (path) {
        if (!path) return '';
        if (/^https?:\/\//i.test(path) || path.startsWith('data:')) return path;
        return API.fileProxyUrl(path);
    },

    fileName: function (url) {
        if (!url) return 'file';
        var name = url.split(/[\\/]/).pop();
        // 会话文件引用形态 {sessionId}:{fileName}，展示时只取文件名部分。
        // 限定 sessionId 为无点号的长标识（UUID），避免误伤文件名里自带冒号的路径
        var refMatch = name.match(/^[0-9a-zA-Z_-]{8,}:(.+)$/);
        if (refMatch) name = refMatch[1];
        return name.replace(/^\d+_/, '');
    },

    previewImage: function (url) {
        var proxyUrl = this.proxy(url);
        // 交给图片查看器组件（components/image-viewer）：在本页浮层里打开，
        // 支持缩放 / 拖动 / 旋转，不再另开标签页。
        // 兜底：万一某页面漏引了组件脚本，退回原来的新标签页行为，不至于点了没反应。
        if (window.ImageViewer) {
            window.ImageViewer.open(proxyUrl);
        } else {
            window.open(proxyUrl, '_blank');
        }
    }
};
