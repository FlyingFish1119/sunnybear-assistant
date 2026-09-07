/**
 * 角色插件 API —— 挂在共享 API 对象上（依赖 api.js 先加载）。
 * 角色插件的 REST/WS 端点在站点根下带 /plug/character 前缀，与 UI 所在目录一致。
 */
(function () {
    if (typeof API === 'undefined' || !API) {
        console.error('character-api.js 需在 api.js 之后加载');
        return;
    }

    /* ---------- 角色 ---------- */
    API.character = {
        get: function (id) { return API.get('plug/character/get?id=' + encodeURIComponent(id)); },
        list: function () { return API.get('plug/character/list'); },
        create: function (data) { return API.post('plug/character/create', data); },
        update: function (data) { return API.post('plug/character/update', data); },
        delete: function (id) { return API.get('plug/character/delete?id=' + encodeURIComponent(id)); },
        activate: function (id) { return API.post('plug/character/activate?id=' + encodeURIComponent(id)); },
        getSessions: function (characterId) {
            return API.get('plug/character/sessions?characterId=' + encodeURIComponent(characterId));
        },
        getBySession: function (sessionId) {
            return API.get('plug/character/get-by-session?sessionId=' + encodeURIComponent(sessionId));
        },
        destroyDb: function (id) {
            return API.get('plug/character/destroy-db?id=' + encodeURIComponent(id));
        },
        deleteBackground: function (id) {
            return API.get('plug/character/delete-background?id=' + encodeURIComponent(id));
        },
        uploadBackground: function (id, file) {
            return API.upload('plug/character/upload-background?id=' + encodeURIComponent(id), file);
        },

        /* 角色数据库表查询 */
        dbTables: function (id) {
            return API.get('plug/character/db-tables?id=' + encodeURIComponent(id));
        },

        /* 角色词条管理 */
        glossary: {
            list: function (characterId) {
                return API.get('plug/character/glossary/list?characterId=' + encodeURIComponent(characterId));
            },
            /** 按关键词/描述模糊搜索词条 */
            search: function (characterId, q) {
                return API.get('plug/character/glossary/search?characterId=' + encodeURIComponent(characterId)
                    + '&q=' + encodeURIComponent(q || ''));
            },
            create: function (data) { return API.post('plug/character/glossary/create', data); },
            update: function (data) { return API.post('plug/character/glossary/update', data); },
            delete: function (id) { return API.get('plug/character/glossary/delete?id=' + id); },
            /** 批量导入词条（JSON 数组 [{keyword, desc, content}]），关键词重复的条目覆盖更新 */
            import: function (characterId, items) {
                return API.post('plug/character/glossary/import?characterId=' + encodeURIComponent(characterId), items);
            }
        },

        /* 战斗回合行动（battle-panel 使用） */
        battle: {
            action: function (action) {
                return API.post('plug/character/battle/action', action);
            }
        }
    };

    /* 角色聊天的 WS 地址 */
    var proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    API.ws = API.ws || {};
    API.ws.characterUrl = proto + window.location.host + API.BASE_PATH + 'plug/character/ws/character-chat';
})();
