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
     * 原始二进制 POST（body 直接是 Blob），供分片上传使用。
     * 不走 multipart，因此不受 spring.servlet.multipart 的大小限制。
     */
    async function postBinary(path, blob) {
        const response = await fetch(BASE_PATH + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: blob
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

        /* ---------- 页面导航 ---------- */
        router: {
            /** 拉取全局页面路由清单（供 router.html 使用） */
            list: function () { return get('router/list'); }
        },

        /* ---------- 分片断点上传（大文件） ---------- */
        upload: {
            /** 初始化 / 恢复上传；data: {uploadId, scope, sessionId, dir, name, size, totalChunks} */
            init: function (data) { return post('upload/init', data); },
            /** 查询已收到的分片序号（断点） */
            status: function (uploadId) { return get('upload/status?uploadId=' + encodeURIComponent(uploadId)); },
            /** 上传单个分片（blob 为该分片原始字节） */
            chunk: function (uploadId, index, blob) {
                return postBinary('upload/chunk?uploadId=' + encodeURIComponent(uploadId) + '&index=' + index, blob);
            },
            /** 合并所有分片并落盘，返回最终相对路径 */
            complete: function (uploadId) { return post('upload/complete?uploadId=' + encodeURIComponent(uploadId)); },
            /** 放弃上传并清理暂存分片 */
            abort: function (uploadId) { return post('upload/abort?uploadId=' + encodeURIComponent(uploadId)); }
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
            toolkits: {
                list: function () { return get('settings/tools/kits'); },
                save: function (data) { return post('settings/toolkit/save', data); }
            },
            mcp: {
                get: function () { return get('settings/mcp/get'); },
                save: function (data) { return post('settings/mcp/save', data); }
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
                list: function () { return get('settings/adapters/list'); },
                /** 拉取指定适配器可选的模型名称列表；未配置 modelUrl 或失败时 data 为空数组 */
                models: function (apiName) { return get('settings/adapters/models?apiName=' + encodeURIComponent(apiName || '')); }
            }
        },

        /* ---------- 会话 ---------- */
        session: {
            getAll: function (type) { return get('session/get/all?type=' + encodeURIComponent(type || 'chat')); },
            /** 按 id 获取单个会话（不存在时 data 为 null） */
            get: function (id) { return get('session/get?sessionId=' + encodeURIComponent(id)); },
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

        /* ---------- 会话文件（文件资源栏） ---------- */
        sessionFile: {
            /** 列出某一层目录；dir 为空表示会话文件根目录 */
            list: function (sessionId, dir) {
                return get('session/file/list?sessionId=' + encodeURIComponent(sessionId)
                    + '&dir=' + encodeURIComponent(dir || ''));
            },
            /** 读文本内容（UTF-8；超过 2MB 后端会拒绝并给出提示） */
            read: function (sessionId, path) {
                return get('session/file/read?sessionId=' + encodeURIComponent(sessionId)
                    + '&path=' + encodeURIComponent(path));
            },
            /** 写回（整文件覆盖） */
            write: function (sessionId, path, content) {
                return post('session/file/write', { sessionId: sessionId, path: path, content: content });
            },
            /** 新建文件（同名已存在会失败，不覆盖） */
            create: function (sessionId, path) {
                return post('session/file/create', { sessionId: sessionId, path: path, content: '' });
            },
            /** 改名 / 移动 */
            rename: function (sessionId, path, newPath) {
                return post('session/file/rename', { sessionId: sessionId, path: path, newPath: newPath });
            },
            /** 删除文件或空目录 */
            remove: function (sessionId, path) {
                return post('session/file/delete', { sessionId: sessionId, path: path });
            },
            /** 上传文件（multipart）；dir 为目标子目录。同名自动加序号，返回落盘相对路径 */
            upload: function (sessionId, file, dir) {
                return upload('session/file/upload?sessionId=' + encodeURIComponent(sessionId)
                    + '&path=' + encodeURIComponent(dir || ''), file);
            },
            /**
             * 文件原始内容的 URL（图片预览 / 下载）。
             * 走 /session/file/raw 而不是 /file/proxy：后者只认 {sessionId}:{文件名} 的引用形态，不吃子目录。
             */
            rawUrl: function (sessionId, path) {
                return BASE_PATH + 'session/file/raw?sessionId=' + encodeURIComponent(sessionId)
                    + '&path=' + encodeURIComponent(path);
            }
        },

        /* ---------- 核心文件（data/core，跨会话长期保存、随时引用） ---------- */
        coreFile: {
            /** 列出某一层目录；dir 为空表示核心目录 */
            list: function (dir) {
                return get('core/file/list?dir=' + encodeURIComponent(dir || ''));
            },
            /** 读文本内容（UTF-8；超过 2MB 后端会拒绝并给出提示） */
            read: function (path) {
                return get('core/file/read?path=' + encodeURIComponent(path));
            },
            /** 写回（整文件覆盖） */
            write: function (path, content) {
                return post('core/file/write', { path: path, content: content });
            },
            /** 新建文件（同名已存在会失败，不覆盖） */
            create: function (path) {
                return post('core/file/create', { path: path, content: '' });
            },
            /** 改名 / 移动 */
            rename: function (path, newPath) {
                return post('core/file/rename', { path: path, newPath: newPath });
            },
            /** 删除文件或空目录 */
            remove: function (path) {
                return post('core/file/delete', { path: path });
            },
            /** 上传文件（multipart）；dir 为目标子目录。同名自动加序号，返回落盘相对路径 */
            upload: function (file, dir) {
                return upload('core/file/upload?path=' + encodeURIComponent(dir || ''), file);
            },
            /** 会话文件转存到核心库（提升为核心），返回落盘相对路径 */
            promote: function (sessionId, path) {
                return post('core/file/promote', { sessionId: sessionId, path: path });
            },
            /** 文件原始内容的 URL（图片预览 / 下载 / 转附件） */
            rawUrl: function (path) {
                return BASE_PATH + 'core/file/raw?path=' + encodeURIComponent(path);
            }
        },

        /* ---------- Shell 终端 ---------- */
        shell: {
            /** 平台信息：默认工作目录、系统名、shell 名 */
            info: function () { return get('shell/info'); },
            /**
             * 往「常驻 shell」里下达一条命令，立即返回 { jobId, cwd, startedAt }。
             * 注意：不再传 cwd —— 会话是持久的，工作目录由命令里的 cd 维持，
             * 起始目录只在 shell 首次启动时生效（后端用项目根）。
             */
            exec: function (command) {
                return post('shell/exec', { command: command });
            },
            /** 按 offset 增量拉输出；返回体里的 offset 是下次该传的值 */
            output: function (jobId, offset) {
                return get('shell/output?jobId=' + encodeURIComponent(jobId) + '&offset=' + (offset || 0));
            },
            /** 终止当前命令（不重建 shell） */
            kill: function (jobId) {
                return post('shell/kill?jobId=' + encodeURIComponent(jobId));
            },
            /** 关闭并重建常驻 shell —— 命令卡死时的逃生口（工作目录/环境会丢） */
            close: function () {
                return post('shell/close');
            }
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
            /** 根据内容重新生成简介（仅返回结果，不落库） */
            introGenerate: function (content) { return post('knowledge/intro/generate', { content: content }); },
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
            delete: function (id) { return get('memory/delete?id=' + encodeURIComponent(id)); },
            /** 分组重命名：组下所有记忆一起改名 */
            groupRename: function (oldName, newName) {
                return post('memory/group/rename', { oldName: oldName, newName: newName });
            },
            /** 自动分组：调用 AI 对全部记忆重新分类 */
            autoGroup: function () { return post('memory/group/auto'); }
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
