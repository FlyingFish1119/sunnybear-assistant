/**
 * API 模块 — 所有后端交互接口的统一入口
 *
 * 用法：
 *   const result = await API.settings.user.get();
 *   const result = await API.session.getAll();
 *   const result = await API.get('some/custom/path');          // 通用 GET
 *   const result = await API.post('some/custom/path', body);   // 通用 POST
 *
 * 响应格式统一为 { status: number, data: any, message?: string }
 * 网络错误会抛出异常，由调用方自行 catch 处理
 */
const API = (function () {
    /* ==================== 根路径 ==================== */
    const BASE_PATH = (function () {
        const path = window.location.pathname;
        const lastSlash = path.lastIndexOf('/');
        return path.substring(0, lastSlash + 1);
    })();

    /* ==================== 底层请求 ==================== */
    async function get(path) {
        const response = await fetch(BASE_PATH + path);
        return response.json();
    }

    async function post(path, body) {
        const response = await fetch(BASE_PATH + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body != null ? JSON.stringify(body) : undefined
        });
        return response.json();
    }

    /**
     * 上传文件（multipart/form-data）。
     * @param {string} path - 接口路径
     * @param {File} file - 文件对象
     * @returns {Promise<{status: number, data: any, message?: string}>}
     */
    async function upload(path, file) {
        const formData = new FormData();
        formData.append('file', file);
        const response = await fetch(BASE_PATH + path, {
            method: 'POST',
            body: formData
        });
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
            return response.json();
        }
        // 非 JSON 响应（如 413 HTML 错误页），构造统一错误对象
        const text = await response.text().catch(() => '');
        return { status: response.status, message: text || ('HTTP ' + response.status) };
    }

    /* ==================== 公开 API ==================== */
    return {
        BASE_PATH,

        /** 通用 GET（适用于未封装为具名函数的路径） */
        get: get,

        /** 通用 POST（适用于未封装为具名函数的路径） */
        post: post,

        ws: {
            url: 'ws://' + window.location.host + BASE_PATH + 'ws/chat',
            characterUrl: 'ws://' + window.location.host + BASE_PATH + 'ws/character-chat'
        },

        /** 文件代理 URL（用于图片/音视频等本地文件的展示） */
        fileProxyUrl: function (path) {
            var ampIdx = path.indexOf('&');
            var actualPath = path;
            var extraParams = '';
            if (ampIdx >= 0) {
                actualPath = path.substring(0, ampIdx);
                extraParams = path.substring(ampIdx);
            }
            return BASE_PATH + 'file/proxy?path=' + encodeURIComponent(actualPath) + extraParams;
        },

        /* ---------- 设置 ---------- */
        settings: {
            user: {
                get: function () { return get('settings/user/get'); },
                save: function (data) { return post('settings/user/save', data); },
                uploadAvatar: function (file) { return upload('settings/user/avatar/upload', file); },
                deleteAvatar: function () { return post('settings/user/avatar/delete'); },
                uploadBackground: function (file) { return upload('settings/user/background/upload', file); },
                deleteBackground: function () { return post('settings/user/background/delete'); }
            },
            assistant: {
                get: function () { return get('settings/assistant/get'); },
                save: function (data) { return post('settings/assistant/save', data); },
                uploadAvatar: function (file) { return upload('settings/assistant/avatar/upload', file); },
                deleteAvatar: function () { return post('settings/assistant/avatar/delete'); }
            },
            chat: {
                get: function () { return get('settings/chat/get'); },
                save: function (data) { return post('settings/chat/save', data); }
            },
            chat_pro: {
                get: function () { return get('settings/chat_pro/get'); },
                save: function (data) { return post('settings/chat_pro/save', data); }
            },
            summary: {
                get: function () { return get('settings/summary/get'); },
                save: function (data) { return post('settings/summary/save', data); }
            },
            title: {
                get: function () { return get('settings/title/get'); },
                save: function (data) { return post('settings/title/save', data); }
            },
            ocr: {
                get: function () { return get('settings/ocr/get'); },
                save: function (data) { return post('settings/ocr/save', data); }
            },
            mission: {
                get: function () { return get('settings/mission/get'); },
                save: function (data) { return post('settings/mission/save', data); }
            },
            task: {
                get: function () { return get('settings/task/get'); },
                save: function (data) { return post('settings/task/save', data); }
            },
            command: {
                get: function () { return get('settings/command/get'); },
                save: function (data) { return post('settings/command/save', data); }
            },
            websearch: {
                get: function () { return get('settings/websearch/get'); },
                save: function (data) { return post('settings/websearch/save', data); }
            },
            filewrite: {
                get: function () { return get('settings/filewrite/get'); },
                save: function (data) { return post('settings/filewrite/save', data); }
            },
            fileedit: {
                get: function () { return get('settings/fileedit/get'); },
                save: function (data) { return post('settings/fileedit/save', data); }
            },
            filedelete: {
                get: function () { return get('settings/filedelete/get'); },
                save: function (data) { return post('settings/filedelete/save', data); }
            },
            filedownload: {
                get: function () { return get('settings/filedownload/get'); },
                save: function (data) { return post('settings/filedownload/save', data); }
            },
            imagecaption: {
                get: function () { return get('settings/imagecaption/get'); },
                save: function (data) { return post('settings/imagecaption/save', data); }
            },
            webreadertool: {
                get: function () { return get('settings/webreadertool/get'); },
                save: function (data) { return post('settings/webreadertool/save', data); }
            },
            extensionscript: {
                get: function () { return get('settings/extensionscript/get'); },
                save: function (data) { return post('settings/extensionscript/save', data); }
            },
            knowledgeapi: {
                get: function () { return get('settings/knowledgeapi/get'); },
                save: function (data) { return post('settings/knowledgeapi/save', data); }
            },
            knowledgesettings: {
                get: function () { return get('settings/knowledgesettings/get'); },
                save: function (data) { return post('settings/knowledge/save', data); }
            },
            adapters: {
                list: function () { return get('settings/adapters/list'); }
            }
        },

        /* ---------- 会话 ---------- */
        session: {
            getAll: function () { return get('session/get/all'); },
            update: function (data) { return post('session/update', data); },
            delete: function (id) { return get('session/delete?id=' + encodeURIComponent(id)); },
            togglePro: function (id) { return post('session/toggle-pro?id=' + encodeURIComponent(id)); }
        },

        /* ---------- 消息 ---------- */
        message: {
            getHistory: function (sessionId) {
                return get('message/history/get?sessionId=' + encodeURIComponent(sessionId));
            },
            switchBranch: function (id, direction) {
                return get('message/branch/switch?id=' + encodeURIComponent(id) + '&direction=' + direction);
            },
            deleteUser: function (userMessageId) {
                return post('message/delete/user?userMessageId=' + encodeURIComponent(userMessageId));
            },
            editAssistant: function (data) {
                return post('message/edit/assistant', data);
            }
        },

        /* ---------- 角色 ---------- */
        character: {
            get: function (id) { return get('character/get?id=' + encodeURIComponent(id)); },
            list: function () { return get('character/list'); },
            create: function (data) { return post('character/create', data); },
            update: function (data) { return post('character/update', data); },
            delete: function (id) { return get('character/delete?id=' + encodeURIComponent(id)); },
            activate: function (data) { return post('character/activate', data); },
            bindSession: function (data) { return post('character/bind-session', data); },
            getSessions: function (characterId) {
                return get('character/sessions?characterId=' + encodeURIComponent(characterId));
            },
            destroyDb: function (id) {
                return get('character/destroy-db?id=' + encodeURIComponent(id));
            },
            deleteBackground: function (id) {
                return get('character/delete-background?id=' + encodeURIComponent(id));
            },
            uploadBackground: function (id, file) {
                return upload('character/upload-background?id=' + encodeURIComponent(id), file);
            },

            /* 角色词条管理 */
            glossary: {
                list: function (characterId) {
                    return get('character/glossary/list?characterId=' + encodeURIComponent(characterId));
                },
                create: function (data) { return post('character/glossary/create', data); },
                update: function (data) { return post('character/glossary/update', data); },
                delete: function (id) { return get('character/glossary/delete?id=' + id); }
            }
        },

        /* ---------- 对话 ---------- */
        chat: {
            /** 回传工具确认结果 */
            confirm: function (data) { return post('chat/confirm', data); },
            /** 中止流式传输 */
            stop: function (data) { return post('chat/stop', data); }
        },

        /* ---------- 知识库 ---------- */
        knowledge: {
            list: function () { return get('knowledge/list'); },
            get: function (id) { return get('knowledge/get?id=' + encodeURIComponent(id)); },
            save: function (data) { return post('knowledge/save', data); },
            delete: function (id) { return get('knowledge/delete?id=' + encodeURIComponent(id)); }
        },

        /* ---------- 记忆 ---------- */
        memory: {
            list: function () { return get('memory/list'); },
            save: function (data) { return post('memory/save', data); },
            delete: function (id) { return get('memory/delete?id=' + encodeURIComponent(id)); }
        },

        /* ---------- 问候语 ---------- */
        greeting: {
            random: function () { return get('greeting/random'); }
        }
    };
})();
