/**
 * 世界观插件 API —— 挂在共享 API 对象上（依赖 api.js 先加载）。
 * 世界插件的 REST/WS 端点在站点根下带 /plug/world 前缀，与 UI 所在目录一致。
 */
(function () {
    if (typeof API === 'undefined' || !API) {
        console.error('world-api.js 需在 api.js 之后加载');
        return;
    }

    /* ---------- 世界观 ---------- */
    API.world = {
        list: function () { return API.get('plug/world/list'); },
        get: function (id) { return API.get('plug/world/get?id=' + encodeURIComponent(id)); },
        create: function (data) { return API.post('plug/world/create', data); },
        update: function (data) { return API.post('plug/world/update', data); },
        delete: function (id) { return API.get('plug/world/delete?id=' + encodeURIComponent(id)); },
        deleteBackground: function (id) {
            return API.get('plug/world/delete-background?id=' + encodeURIComponent(id));
        },
        uploadBackground: function (id, file) {
            return API.upload('plug/world/upload-background?id=' + encodeURIComponent(id), file);
        },
        /** 获取绑定到某世界观的全部群聊会话 */
        getSessions: function (worldId) {
            return API.get('plug/world/sessions?worldId=' + encodeURIComponent(worldId));
        },
        /** 导出世界观为 JSON 文件数据（不含头像/背景图，知识按角色名关联） */
        exportWorld: function (id) {
            return API.get('plug/world/export?id=' + encodeURIComponent(id));
        },
        /** 导入世界观 JSON：targetWorldId 为空 = 新建世界观，否则覆盖该世界观 */
        importWorld: function (data, targetWorldId) {
            return API.post('plug/world/import' + (targetWorldId ? '?targetWorldId=' + encodeURIComponent(targetWorldId) : ''), data);
        },

        /* 世界观下的群组角色（id 主键） */
        character: {
            list: function (worldId) {
                return API.get('plug/world/character/list?worldId=' + encodeURIComponent(worldId));
            },
            get: function (id) {
                return API.get('plug/world/character/get?id=' + encodeURIComponent(id));
            },
            create: function (data) { return API.post('plug/world/character/create', data); },
            update: function (data) { return API.post('plug/world/character/update', data); },
            delete: function (id) {
                return API.get('plug/world/character/delete?id=' + encodeURIComponent(id));
            }
        },

        /* 世界观下的知识（标题 + 内容 + 知晓角色） */
        knowledge: {
            list: function (worldId) {
                return API.get('plug/world/knowledge/list?worldId=' + encodeURIComponent(worldId));
            },
            get: function (id) {
                return API.get('plug/world/knowledge/get?id=' + encodeURIComponent(id));
            },
            create: function (data) { return API.post('plug/world/knowledge/create', data); },
            update: function (data) { return API.post('plug/world/knowledge/update', data); },
            delete: function (id) {
                return API.get('plug/world/knowledge/delete?id=' + encodeURIComponent(id));
            }
        }
    };

    /* 世界群聊的 WS 地址 */
    var proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    API.ws = API.ws || {};
    API.ws.worldUrl = proto + window.location.host + API.BASE_PATH + 'plug/world/ws/world-chat';
})();
