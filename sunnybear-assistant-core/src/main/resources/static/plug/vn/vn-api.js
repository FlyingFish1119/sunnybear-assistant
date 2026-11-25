/**
 * 视觉小说插件 API —— 挂在共享 API 对象上（依赖 api.js 先加载）。
 * 视觉小说插件的 REST 端点在站点根下带 /plug/vn 前缀，与 UI 所在目录一致。
 */
(function () {
    if (typeof API === 'undefined' || !API) {
        console.error('vn-api.js 需在 api.js 之后加载');
        return;
    }

    API.vn = {
        /** 书架：全部小说摘要 */
        list: function () { return API.get('plug/vn/list'); },
        /** 单部小说详情：{ data, chapters, saves, assets } */
        get: function (title) { return API.get('plug/vn/get?title=' + encodeURIComponent(title)); },
        /** 删除整部小说（含章节 / 存档 / 资源） */
        deleteNovel: function (title) { return API.get('plug/vn/delete?title=' + encodeURIComponent(title)); },
        /** 章节节点字典：nodeId → node；章节不存在时 message 以「章节不存在:」开头 */
        getChapterNodes: function (title, chapterId) {
            return API.get('plug/vn/chapter/nodes?title=' + encodeURIComponent(title)
                + '&chapterId=' + encodeURIComponent(chapterId));
        },

        /* ---------- 存档 ---------- */
        listSaves: function (title) { return API.get('plug/vn/save/list?title=' + encodeURIComponent(title)); },
        getSave: function (title, name) {
            return API.get('plug/vn/save/get?title=' + encodeURIComponent(title)
                + '&name=' + encodeURIComponent(name));
        },
        writeSave: function (title, name, saveData) {
            return API.post('plug/vn/save/write?title=' + encodeURIComponent(title)
                + '&name=' + encodeURIComponent(name), saveData);
        },
        deleteSave: function (title, name) {
            return API.get('plug/vn/save/delete?title=' + encodeURIComponent(title)
                + '&name=' + encodeURIComponent(name));
        },

        /** 图片资源 URL（后端对缺图 / 0 字节文件返回 404，由前端 @error 兜底渲染占位） */
        assetUrl: function (title, fileName) {
            if (!title || !fileName) {
                return '';
            }
            return API.BASE_PATH + 'plug/vn/asset?title=' + encodeURIComponent(title)
                + '&name=' + encodeURIComponent(fileName);
        }
    };
})();
