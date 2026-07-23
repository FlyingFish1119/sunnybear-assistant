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
        return name.replace(/^\d+_/, '');
    },

    previewImage: function (url) {
        var proxyUrl = this.proxy(url);
        window.open(proxyUrl, '_blank');
    }
};
