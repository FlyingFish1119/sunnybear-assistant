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

    // WebSocket 协议跟随页面协议：https 页面必须用 wss（浏览器禁止混合内容）
    const WS_PROTO = window.location.protocol === 'https:' ? 'wss://' : 'ws://';

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
            url: WS_PROTO + window.location.host + BASE_PATH + 'ws/chat',
            characterUrl: WS_PROTO + window.location.host + BASE_PATH + 'ws/character-chat',
            worldUrl: WS_PROTO + window.location.host + BASE_PATH + 'ws/world-chat'
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
            cub: {
                get: function () { return get('settings/cub/get'); },
                save: function (data) { return post('settings/cub/save', data); }
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
            getAll: function (type) { return get('session/get/all?type=' + encodeURIComponent(type || 'chat')); },
            update: function (data) { return post('session/update', data); },
            delete: function (id) { return get('session/delete?id=' + encodeURIComponent(id)); },
            togglePro: function (id) { return post('session/toggle-pro?id=' + encodeURIComponent(id)); },
            toggleUnreviewed: function (id) { return post('session/toggle-unreviewed?id=' + encodeURIComponent(id)); }
        },

        /* ---------- 定时任务 ---------- */
        cronJob: {
            list: function () { return get('cron-job/list'); },
            get: function (id) { return get('cron-job/get?id=' + encodeURIComponent(id)); },
            save: function (data) { return post('cron-job/save', data); },
            delete: function (id) { return get('cron-job/delete?id=' + encodeURIComponent(id)); }
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

            /* 角色数据库表查询 */
            dbTables: function (id) {
                return get('character/db-tables?id=' + encodeURIComponent(id));
            },

            /* 角色词条管理 */
            glossary: {
                list: function (characterId) {
                    return get('character/glossary/list?characterId=' + encodeURIComponent(characterId));
                },
                /** 按关键词/描述模糊搜索词条 */
                search: function (characterId, q) {
                    return get('character/glossary/search?characterId=' + encodeURIComponent(characterId)
                        + '&q=' + encodeURIComponent(q || ''));
                },
                create: function (data) { return post('character/glossary/create', data); },
                update: function (data) { return post('character/glossary/update', data); },
                delete: function (id) { return get('character/glossary/delete?id=' + id); },
                /** 批量导入词条（JSON 数组 [{keyword, desc, content}]），关键词重复的条目覆盖更新 */
                import: function (characterId, items) {
                    return post('character/glossary/import?characterId=' + encodeURIComponent(characterId), items);
                }
            }
        },

        /* ---------- 世界观 ---------- */
        world: {
            list: function () { return get('world/list'); },
            get: function (id) { return get('world/get?id=' + encodeURIComponent(id)); },
            create: function (data) { return post('world/create', data); },
            update: function (data) { return post('world/update', data); },
            delete: function (id) { return get('world/delete?id=' + encodeURIComponent(id)); },
            deleteBackground: function (id) {
                return get('world/delete-background?id=' + encodeURIComponent(id));
            },
            uploadBackground: function (id, file) {
                return upload('world/upload-background?id=' + encodeURIComponent(id), file);
            },
            /** 绑定群聊会话到世界观 */
            bindSession: function (data) { return post('world/bind-session', data); },
            /** 解绑群聊会话 */
            unbindSession: function (sessionId) {
                return get('world/unbind-session?sessionId=' + encodeURIComponent(sessionId));
            },
            /** 通过会话 ID 获取绑定的世界观 */
            getBySession: function (sessionId) {
                return get('world/get-by-session?sessionId=' + encodeURIComponent(sessionId));
            },
            /** 获取绑定到某世界观的全部群聊会话 */
            getSessions: function (worldId) {
                return get('world/sessions?worldId=' + encodeURIComponent(worldId));
            },

            /* 世界观下的群组角色（id 主键） */
            character: {
                list: function (worldId) {
                    return get('world/character/list?worldId=' + encodeURIComponent(worldId));
                },
                get: function (id) {
                    return get('world/character/get?id=' + encodeURIComponent(id));
                },
                create: function (data) { return post('world/character/create', data); },
                update: function (data) { return post('world/character/update', data); },
                delete: function (id) {
                    return get('world/character/delete?id=' + encodeURIComponent(id));
                }
            },

            /* 世界观下的知识（标题 + 内容 + 知晓角色） */
            knowledge: {
                list: function (worldId) {
                    return get('world/knowledge/list?worldId=' + encodeURIComponent(worldId));
                },
                get: function (id) {
                    return get('world/knowledge/get?id=' + encodeURIComponent(id));
                },
                create: function (data) { return post('world/knowledge/create', data); },
                update: function (data) { return post('world/knowledge/update', data); },
                delete: function (id) {
                    return get('world/knowledge/delete?id=' + encodeURIComponent(id));
                }
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
            delete: function (id) { return get('knowledge/delete?id=' + encodeURIComponent(id)); },
            /** 查询某会话已注入的知识条目列表 */
            sessionList: function (sessionId) { return get('knowledge/session/list?sessionId=' + encodeURIComponent(sessionId)); },
            /** 从会话注入列表中移除一条知识条目 */
            sessionRemove: function (sessionId, knowledgeId) {
                return get('knowledge/session/remove?sessionId=' + encodeURIComponent(sessionId)
                    + '&knowledgeId=' + encodeURIComponent(knowledgeId));
            },
            /** 清空某会话的全部知识注入记录 */
            sessionClear: function (sessionId) { return get('knowledge/session/clear?sessionId=' + encodeURIComponent(sessionId)); }
        },

        /* ---------- 记忆 ---------- */
        memory: {
            list: function () { return get('memory/list'); },
            save: function (data) { return post('memory/save', data); },
            delete: function (id) { return get('memory/delete?id=' + encodeURIComponent(id)); }
        },

        /* ---------- 任务提示词 ---------- */
        taskPrompt: {
            list: function () { return get('task-prompt/list'); },
            get: function (type) { return get('task-prompt/get?type=' + encodeURIComponent(type)); },
            save: function (data) { return post('task-prompt/save', data); },
            delete: function (type) { return get('task-prompt/delete?type=' + encodeURIComponent(type)); }
        },

        /* ---------- 问候语 ---------- */
        greeting: {
            random: function () { return get('greeting/random'); }
        }
    };
})();
