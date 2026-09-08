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
    // 站点根（含可能的部署 context path）：取 api.js 自身所在目录。
    // 页面可能被放在子目录（如 /plug/character/index.html），因此不能取“当前页面目录”，
    // 否则共享接口会被拼成 /plug/character/... 前缀。各插件的 REST/WS 也已挂到 /plug/<插件>/ 下，由插件自己的 api 文件按全路径调用。
    const BASE_PATH = (function () {
        const script = document.currentScript;
        if (script && script.src) {
            const src = script.src;
            const lastSlash = src.lastIndexOf('/');
            return src.substring(0, lastSlash + 1).replace(/^[a-z]+:\/\/[^/]*/i, '');
        }
        // 兜底：取页面协议+主机之后到第一个路径段（含 context path）
        const path = window.location.pathname;
        const idx = path.indexOf('/', 1);
        return idx < 0 ? '/' : path.substring(0, idx + 1);
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

        /** 通用文件上传（multipart/form-data），供各插件 API 复用 */
        upload: upload,

        ws: {
            url: WS_PROTO + window.location.host + BASE_PATH + 'ws/chat'
            // 插件（角色/世界）的 ws 地址由各插件自己的 api 文件补充：API.ws.characterUrl / API.ws.worldUrl
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
            memorysettings: {
                get: function () { return get('settings/memorysettings/get'); },
                save: function (data) { return post('settings/memorysettings/save', data); }
            },
            adapters: {
                list: function () { return get('settings/adapters/list'); }
            }
        },

        /* ---------- 会话 ---------- */
        session: {
            getAll: function (type) { return get('session/get/all?type=' + encodeURIComponent(type || 'chat')); },
            /**
             * keyset 分页获取会话（侧边栏无限滚动用）。
             * 首屏不传 beforeTime/beforeId；翻页传上一页最旧一条的 updateTime + id。
             * @returns {Promise<{status:number,data:{list:Array,hasMore:boolean}}>}
             */
            page: function (type, size, beforeTime, beforeId) {
                var qs = 'type=' + encodeURIComponent(type || 'chat') + '&size=' + (size || 50);
                if (beforeTime != null && beforeId != null) {
                    qs += '&beforeTime=' + encodeURIComponent(beforeTime) + '&beforeId=' + encodeURIComponent(beforeId);
                }
                return get('session/get/page?' + qs);
            },
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

        /* ---------- 角色 / 世界观 API：已抽到各插件目录（plug/character/character-api.js、plug/world/world-api.js） ---------- */

        /* ---------- 对话 ---------- */
        chat: {
            /** 回传工具确认结果 */
            confirm: function (data) { return post('chat/confirm', data); },
            /** 回传结构化提问作答结果 */
            question: function (data) { return post('chat/question', data); },
            /** 中止流式传输 */
            stop: function (sessionId) { return post('chat/stop?sessionId=' + encodeURIComponent(sessionId)); }
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
