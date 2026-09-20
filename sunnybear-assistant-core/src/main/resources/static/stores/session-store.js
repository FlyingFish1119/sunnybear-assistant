/**
 * SessionStore — 会话与消息状态仓库
 *
 * 把原先散落在页面主组件的「当前会话 / 消息列表 / 流式状态」及其数据操作
 * 整体内聚到这里，通过 app.provide('sessionStore', SessionStore) 注入后代组件，
 * 组件用 inject 直接读写，不必再由父级层层传 props。
 *
 * 负责：
 *   - 状态：sessions / sessionsHasMore / sessionsLoadingMore / sessionListMode /
 *           currentSession / currentMessages / streamingMap / currentToolCallId /
 *           sessionSelectLoading / sendingMap
 *   - 派生：currentSessionId / isStreaming / isNewSession
 *   - 数据方法：会话列表（分页/刷新/增删/sort/Pro/无审查）、会话切换、消息增删、
 *     流式标记、工具占位替换、各类 WS 帧的数据处理
 *   - WS 路由：加载时自行订阅 WsBus，精确处理各 ###SIGNAL### 帧，
 *     并订阅 '*' 兜底处理 JSON 状态帧（原 index.handleStreamChunk 全部逻辑）。
 *
 * 不负责（UI 副作用）：滚动、Mermaid 渲染、TTS 播放、右键菜单/弹窗等。
 * 这些通过 ui 钩子对象交由页面主组件实现（registerUi(hooks)）或留在组件内；
 * 关闭侧边栏则经 WsBus 本地事件 'sidebar:close' 通知 chat-sidebar，
 * 从而把「数据」与「DOM/TTS」解耦，同时保持所有既有行为。
 *
 * 典型用法：
 *   // 页面主组件
 *   SessionStore.registerUi({ scrollToBottom, renderMermaid, ... });
 *   SessionStore.selectSession(session);
 *
 *   // 任意后代组件
 *   inject: ['sessionStore']
 *   computed: { sessionId() { return this.sessionStore.currentSessionId; } }
 */
const SessionStore = (function () {
    const { reactive } = Vue;

    /** 会话列表每页条数（与后端 /session/get/page 的 size 默认一致） */
    const SESSION_PAGE_SIZE = 50;

    /**
     * 会话按 (updateTime, id) 降序比较 —— 与后端 /session/get/page 的排序一致。
     * updateTime 为 "yyyy-MM-dd HH:mm:ss" 文本，字典序即时间序；id 仅作同秒内稳定平局裁决。
     */
    function compareSessionDesc(a, b) {
        const at = a.updateTime || '';
        const bt = b.updateTime || '';
        if (at !== bt) return at < bt ? 1 : -1;
        const ai = a.id || '';
        const bi = b.id || '';
        if (ai === bi) return 0;
        return ai < bi ? 1 : -1;
    }

    /** UI 副作用钩子（由页面主组件通过 registerUi 注入，默认空实现） */
    const ui = {
        scrollToBottom: function () {},
        renderMermaid: function () {},
        enqueueTts: function () {},
        playMessageAudio: function () {},
        clearTts: function () {},
        isTtsEnabled: function () { return false; },
        clearMdCache: function () {},
        clearSendArea: function () {}
    };

    const state = reactive({
        /** 会话列表（已加载子集，始终按 updateTime 降序，单一数据源） */
        sessions: [],
        /** 是否还有更早的会话可加载（触底翻页后由服务端 hasMore 更新） */
        sessionsHasMore: true,
        /** 触底加载更早一页是否进行中 */
        sessionsLoadingMore: false,
        /** 会话列表模式：chat / cron */
        sessionListMode: 'chat',
        /** 当前正在处理的 session（对象引用，指向 state.sessions 里的同一对象） */
        currentSession: {},
        /** 当前正在展示的 messages */
        currentMessages: [],
        /** 流式标记表：key = sessionId，各会话只看自己的标记，互不影响 */
        streamingMap: {},
        /**
         * 上下文压缩状态表：key = sessionId，value = 'running' | 'done'。
         * running：正在调用模型总结旧对话（START 之后的长空窗）；done：压缩完成、成功态短暂展示。
         * 消息区据此渲染压缩卡片，避免前端在等待回复处呆等。
         */
        compressMap: {},
        /**
         * 本轮「请求模型」状态表：key = sessionId，value = 'connecting' | 'thinking'。
         * connecting：正在与模型建连（REQUEST_CONNECTING，connect 返回前，网络/网关慢时会停留）；
         * thinking：连上了、模型还没吐出第一条内容（REQUEST_THINKING）；
         * 首个产出帧到达（或本轮结束/出错/断线）即清除，消息区据此在在途气泡上显示状态。
         */
        requestStateMap: {},
        /** 当前正在处理的 tool call id */
        currentToolCallId: null,
        /** 会话历史加载中 */
        sessionSelectLoading: false,
        /**
         * 各会话的「请求在途」标记：key = sessionId（新会话未定 id 时用 '' 占位），
         * send / edit / replace 发起时立即置 true，直到该会话 END 帧（本轮结束）或出错/断线才复位。
         * 按会话隔离，避免在 A 会话发送时把 B 会话的发送按钮也锁死。
         */
        sendingMap: {}
    });

    /** 判断某条消息是否为「在途流式占位」（以 streaming_ 前缀标识 id） */
    function isStreamingPlaceholder(msg, sessionId) {
        return msg
            && msg.role === 'assistant'
            && msg.id
            && String(msg.id).startsWith('streaming_')
            && (sessionId == null || msg.sessionId === sessionId);
    }

    /** 生成一条默认的 assistant 流式占位消息（临时 id，避免 :key 为 undefined） */
    function getDefaultAssistantMessage(sessionId) {
        return {
            id: 'streaming_' + Date.now(),
            sessionId: sessionId,
            parentId: "",
            role: 'assistant',
            reasoningContent: "",
            contents: [{ type: 'text', content: "" }],
            toolCalls: []
        };
    }

    /** 当前会话 id（currentSession 可能为 {}，此时返回 undefined，语义与旧 currentSessionId 一致） */
    function currentSessionId() {
        return state.currentSession.id;
    }

    /** 在途标记的 key：新会话尚未拿到 id 时统一用空串，避免 undefined 键 */
    function sendingKey(sessionId) {
        return sessionId == null ? '' : sessionId;
    }

    /** 清除某会话的在途标记（同时兜底清掉新会话的空串占位） */
    function clearSendingKey(sessionId) {
        delete state.sendingMap[sendingKey(sessionId)];
        delete state.sendingMap[''];
    }

    /** 标记开始发送（本轮结束前禁止重复提交），返回是否成功占位 */
    function beginSend() {
        const key = sendingKey(currentSessionId());
        if (state.sendingMap[key]) return false;
        state.sendingMap[key] = true;
        ui.clearTts();
        return true;
    }

    /** 组装带当前 sessionId / 朗读开关的聊天请求并发送（replace / edit 等复用） */
    function sendChatRequest(extra) {
        const ws = WsBus.getSocket();
        if (!ws) {
            console.error('WebSocket 未连接，请求发送失败');
            return;
        }
        ws.send(JSON.stringify(Object.assign({
            sessionId: currentSessionId(),
            tts: ui.isTtsEnabled()
        }, extra)));
    }

    /** 取数组最后一项（保持与旧 Utils.getLast 一致） */
    function getLast(arr) {
        if (arr === null || arr.length === 0) {
            return null;
        }
        return arr[arr.length - 1];
    }

    /* ================= 流式文本合帧 ================= */
    /**
     * chunk / done 文本帧缓冲。后端可能在单个动画帧内下发多帧，逐帧写 reactive
     * 会让消息区每帧多次整列重渲染；这里攒到下一次 rAF 一次性应用，把渲染频率
     * 压到屏幕刷新率以内，并让同一批内容只触发一次更新。
     * 其它类型的帧到达时必须先 flush，以保证文本与工具/结束帧的先后顺序。
     */
    const pendingChunks = [];
    let chunkFlushHandle = null;

    /** 立刻应用缓冲区内的全部 chunk/done 帧（顺序处理，最后只滚动一次） */
    function flushPendingChunks() {
        if (chunkFlushHandle !== null) {
            cancelAnimationFrame(chunkFlushHandle);
            chunkFlushHandle = null;
        }
        if (pendingChunks.length === 0) return;
        const batch = pendingChunks.splice(0, pendingChunks.length);
        let appended = false;
        for (const response of batch) {
            const streamingMessage = store.findStreamingMessage(response.sessionId);
            if (!streamingMessage) continue;
            store.appendChunk(streamingMessage, response);
            appended = true;
        }
        if (appended) ui.scrollToBottom();
    }

    /** 缓冲一帧 chunk/done，并在下一次 rAF 统一应用 */
    function queueChunk(response) {
        pendingChunks.push(response);
        if (chunkFlushHandle === null) {
            chunkFlushHandle = requestAnimationFrame(flushPendingChunks);
        }
    }

    /** 丢弃未应用的缓冲（断线/重建消息等场景，避免把过期内容写到新消息上） */
    function discardPendingChunks() {
        if (chunkFlushHandle !== null) {
            cancelAnimationFrame(chunkFlushHandle);
            chunkFlushHandle = null;
        }
        pendingChunks.length = 0;
    }

    /**
     * 用真实工具结果替换对应的"执行中"占位消息（按 toolCallId 精确匹配）。
     * 找不到占位时（如页面刷新后）直接追加。
     * @param {object} resultMsg 工具结果消息
     */
    function replaceToolPlaceholder(resultMsg) {
        if (!resultMsg || !resultMsg.toolCallId) return;
        // 优先替换"执行中"占位
        let idx = state.currentMessages.findIndex(m =>
            m.role === 'tool'
            && m.extension && m.extension.status === 'executing'
            && m.toolCallId === resultMsg.toolCallId);
        // 重放场景：占位可能已被历史里的落库消息取代，按 id / toolCallId 定位，避免重复追加
        if (idx === -1 && resultMsg.id) {
            idx = state.currentMessages.findIndex(m => m.id === resultMsg.id);
        }
        if (idx === -1) {
            idx = state.currentMessages.findIndex(m =>
                m.role === 'tool' && m.toolCallId === resultMsg.toolCallId);
        }
        if (idx !== -1) {
            state.currentMessages.splice(idx, 1, resultMsg);
        } else {
            state.currentMessages.push(resultMsg);
        }
        ui.scrollToBottom();
    }

    const store = {
        /** 注入后代组件的 key 名（供文档/常量引用） */
        provideKey: 'sessionStore',

        /** 响应式状态（组件可直接读写；state.currentSession 等） */
        state: state,

        /* ================= 派生属性 ================= */

        get sessions() { return state.sessions; },
        get sessionsHasMore() { return state.sessionsHasMore; },
        get sessionsLoadingMore() { return state.sessionsLoadingMore; },
        get sessionListMode() { return state.sessionListMode; },
        get currentSession() { return state.currentSession; },
        get currentMessages() { return state.currentMessages; },
        get currentSessionId() { return currentSessionId(); },
        get isNewSession() { return currentSessionId() === undefined; },
        get isStreaming() { return !!state.streamingMap[currentSessionId()]; },
        /** 当前会话的上下文压缩状态：'running' | 'done' | null */
        get compressState() { return state.compressMap[currentSessionId()] || null; },
        /** 当前会话的本轮请求状态：'connecting' | 'thinking' | null */
        get requestState() { return state.requestStateMap[currentSessionId()] || null; },
        get sessionSelectLoading() { return state.sessionSelectLoading; },
        /** 当前会话是否有请求在途（仅看当前会话，不影响其它会话的发送状态） */
        get sending() { return !!state.sendingMap[sendingKey(currentSessionId())]; },
        /** 当前会话本轮是否不可交互：请求在途（send/edit/replace）或正在流式输出 */
        get busy() {
            const sid = currentSessionId();
            return !!state.sendingMap[sendingKey(sid)] || !!state.streamingMap[sid];
        },

        /**
         * 注册 UI 副作用钩子（页面主组件在 mounted 时调用一次）。
         * @param {object} hooks 覆盖 ui 中的对应方法
         */
        registerUi(hooks) {
            Object.assign(ui, hooks || {});
        },

        /* ================= 会话操作 ================= */

        /**
         * 切换到指定会话：拉取历史消息、请求总线续传、更新加载态。
         * UI 副作用（关闭抽屉、清 TTS/日志、滚动、Mermaid）经 ui 钩子执行。
         * @param {object} session 会话对象
         */
        async selectSession(session) {
            // 已有会话正在加载：忽略后续切换，避免两次请求的历史互相覆盖，出现串台
            if (state.sessionSelectLoading) return;
            WsBus.emit('sidebar:close');
            // 切走时丢弃尚未落盘的缓冲：其目标消息即将被历史覆盖，避免写到新会话上
            discardPendingChunks();
            ui.clearMdCache();
            WsBus.emit('agent-log:clear');
            ui.clearTts();
            state.sessionSelectLoading = true;
            // 切到不同会话时清空旧消息，让居中的加载态可见；
            // 同会话重选（切换分支 / 删除消息后的刷新）保留消息，避免闪一下加载
            const switched = !state.currentSession || state.currentSession.id !== session.id;
            state.currentSession = session;
            if (switched) {
                state.currentMessages = [];
                // 切走时丢弃上一个会话的压缩卡片与请求状态（其信号不会再投递到当前连接）
                state.compressMap = {};
                state.requestStateMap = {};
            }
            try {
                const result = await API.message.getHistory(session.id);
                if (result.status === 200) {
                    state.currentMessages = result.data;
                    ui.scrollToBottom(true);
                    // 请求该会话的总线续传：若当前有一轮在进行中，服务端回放缓冲事件重建在途消息
                    const ws = WsBus.getSocket();
                    if (ws && ws.readyState === 1) {
                        ws.send("###REQUIRE_REPLAY_MESSAGE###" + session.id);
                    }
                } else {
                    ElementPlus.ElMessage.error(result.message || '获取会话历史消息失败');
                }
                Vue.nextTick(() => {
                    ui.renderMermaid();
                });
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('获取会话历史消息失败:', error);
            } finally {
                state.sessionSelectLoading = false;
            }
        },

        /** 新建会话：清空当前会话与消息 */
        createSession() {
            // 会话仍在加载时不允许新建，否则会与在途的历史请求竞态
            if (state.sessionSelectLoading) return;
            WsBus.emit('sidebar:close');
            discardPendingChunks();
            ui.clearMdCache();
            WsBus.emit('agent-log:clear');
            state.currentSession = {};
            state.currentMessages = [];
            state.compressMap = {};
            state.requestStateMap = {};
            // 广播"用户开了个新对话"（放在 return 之后：没真开成就不吭声）
            WsBus.emit('session:created');
        },

        /* ================= 会话列表 ================= */

        /**
         * 刷新会话列表（根据当前 listMode）：拉最新一页用于纠正顶部排序 / 补入新会话，
         * 并保留此前已加载的更早记录，避免打断正在滚动查看的历史。
         */
        async refreshSessions() {
            try {
                const result = await API.session.page(state.sessionListMode, SESSION_PAGE_SIZE);
                if (result.status === 200 && result.data) {
                    const top = result.data.list || [];
                    const hasMoreTop = !!result.data.hasMore;
                    // 已在内存、但不在最新一页里的旧记录 = 用户翻到的更早历史，原样保留
                    const topIds = new Set(top.map(s => s.id));
                    const tail = state.sessions.filter(s => !topIds.has(s.id));
                    state.sessions = top.concat(tail).sort(compareSessionDesc);
                    if (hasMoreTop) {
                        state.sessionsHasMore = true;
                    } else if (tail.length === 0) {
                        // 首页不满一页且无旧尾部 → 已全部加载完
                        state.sessionsHasMore = false;
                    }
                    // hasMoreTop=false 但保留了旧尾部：曾加载过更早记录，保持原 hasMore
                    // 重指向当前会话，避免刷新后 currentSession 仍引用旧数组里的对象
                    if (currentSessionId()) {
                        const ref = state.sessions.find(s => s.id === currentSessionId());
                        if (ref) state.currentSession = ref;
                    }
                }
            } catch (error) {
                console.error('获取会话列表失败:', error);
            }
        },

        /**
         * 触底加载更早一页：以当前最旧一条的 (updateTime, id) 作 keyset 游标请求服务端，
         * 天然不重不漏、不受排序漂移影响。
         */
        async loadMoreSessions() {
            if (state.sessionsLoadingMore || !state.sessionsHasMore || state.sessions.length === 0) return;
            const last = state.sessions[state.sessions.length - 1];
            if (!last || !last.id || !last.updateTime) return;
            state.sessionsLoadingMore = true;
            try {
                const result = await API.session.page(
                    state.sessionListMode, SESSION_PAGE_SIZE, last.updateTime, last.id);
                if (result.status === 200 && result.data) {
                    const list = result.data.list || [];
                    const existingIds = new Set(state.sessions.map(s => s.id));
                    const fresh = list.filter(s => !existingIds.has(s.id));
                    state.sessions = state.sessions.concat(fresh).sort(compareSessionDesc);
                    state.sessionsHasMore = !!result.data.hasMore;
                }
            } catch (error) {
                console.error('加载更多会话失败:', error);
            } finally {
                state.sessionsLoadingMore = false;
            }
        },

        /** 切换列表模式：chat ↔ cron（清空列表并重置分页状态后重拉） */
        toggleSessionListMode() {
            state.sessionListMode = state.sessionListMode === 'chat' ? 'cron' : 'chat';
            state.sessions = [];
            state.sessionsHasMore = true;
            this.refreshSessions();
        },

        /** 按 id 查找会话对象（返回 state.sessions 中的引用） */
        getSessionById(id) {
            return state.sessions.find(s => s.id === id);
        },

        /**
         * 新增或更新一个会话条目（###UPDATE_SESSION### 等）。
         * 更新后按 (updateTime, id) 重排（会话被新消息顶到最前时位置随之移动）。
         * @param {object} session 服务端下发的会话对象
         * @returns {object} 列表中的会话对象引用
         */
        upsertSession(session) {
            let existing = state.sessions.find(s => s.id === session.id);
            if (existing) {
                Object.assign(existing, session);
            } else {
                existing = session;
                state.sessions.push(session);
            }
            state.sessions.sort(compareSessionDesc);
            if (session.id === currentSessionId()) {
                state.currentSession = existing;
            }
            return existing;
        },

        /**
         * 删除会话：调用接口 → 从列表移除 → 若为当前会话则清空视图。
         * @returns {Promise<boolean>} 是否删除成功
         */
        async deleteSession(session) {
            try {
                const result = await API.session.delete(session.id);
                if (result.status === 200) {
                    ElementPlus.ElMessage.success('会话已删除');
                    // 先记下"删的是不是当前会话"：清空之后 currentSessionId() 就查不出来了
                    const wasCurrent = state.currentSession === session || currentSessionId() === session.id;
                    const idx = state.sessions.findIndex(s => s.id === session.id);
                    if (idx !== -1) {
                        state.sessions.splice(idx, 1);
                    }
                    if (wasCurrent) {
                        state.currentSession = {};
                        state.currentMessages = [];
                    }
                    // 删掉哪一个都广播（不分是不是当前会话）：看板熊就爱凑这个热闹
                    WsBus.emit('session:deleted');
                    return true;
                }
                ElementPlus.ElMessage.error(result.message || '删除会话失败');
                return false;
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('删除会话失败:', error);
                return false;
            }
        },

        /** 切换会话 Pro 模式（普通 ↔ 高级），并同步列表与当前会话对象 */
        async toggleSessionPro(session) {
            const enabling = !session.enablePro;
            try {
                const result = await API.session.togglePro(session.id);
                if (result.status === 200) {
                    const target = state.sessions.find(s => s.id === session.id);
                    if (target) Object.assign(target, result.data);
                    if (currentSessionId() === session.id) {
                        Object.assign(state.currentSession, result.data);
                    }
                    // 广播"用户主动拨的 Pro 开关"（同无审查：切会话/加载数据不该被当成切换）
                    WsBus.emit('session:pro-toggled', { sessionId: session.id, enabling: enabling });
                } else {
                    ElementPlus.ElMessage.error(result.message || '切换模式失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error('切换模式失败:', error);
            }
        },

        /** 切换会话无审查模式（审查中 ↔ 无审查），并同步列表与当前会话对象 */
        async toggleSessionUnreviewed(session) {
            const enabling = !session.unreviewed;
            try {
                const result = await API.session.toggleUnreviewed(session.id);
                if (result.status === 200) {
                    const target = state.sessions.find(s => s.id === session.id);
                    if (target) Object.assign(target, result.data);
                    if (currentSessionId() === session.id) {
                        Object.assign(state.currentSession, result.data);
                    }
                    ElementPlus.ElMessage.success(enabling ? '已开启无审查模式' : '已关闭无审查模式');
                    // 广播"这是用户主动拨的开关"：看板熊靠它区分"切模式"和"切会话"——
                    // 换会话/加载数据同样会改 unreviewed 的值，那种不该当成切换
                    WsBus.emit('session:unreviewed-toggled', { sessionId: session.id, enabling: enabling });
                } else {
                    ElementPlus.ElMessage.error(result.message || '切换无审查模式失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error('切换无审查模式失败:', error);
            }
        },

        /* ================= 发送 ================= */

        /** 标记开始发送（等待服务端 init_user 确认期间禁止重复提交，见文件顶部 beginSend） */
        beginSend,

        /**
         * 组装并发送一条聊天消息（payload 由发送区提供：{ content, files, tts }）。
         * 新会话用 create、已有会话用 append；处于 sending（等确认）或未连接时忽略。
         * @param {object} payload
         * @returns {boolean} 是否已发出
         */
        sendMessage(payload) {
            if (!beginSend()) return false;
            const sessionId = currentSessionId();
            const request = {
                mode: sessionId ? 'append' : 'create',
                sessionId: sessionId,
                content: payload.content,
                tts: payload.tts
            };
            if (payload.files && payload.files.length > 0) {
                request.files = payload.files;
            }
            const ws = WsBus.getSocket();
            if (ws) {
                ws.send(JSON.stringify(request));
            } else {
                console.error('WebSocket 未连接，消息发送失败');
            }
            return true;
        },

        /** 中止当前会话的流式传输（清播放队列并通知服务端 stop） */
        stopStreaming() {
            console.log('Abort streaming');
            const sessionId = currentSessionId();
            if (!sessionId || !state.streamingMap[sessionId]) return;
            ui.clearTts();
            API.chat.stop(sessionId).catch(err => console.error('中止请求发送失败:', err));
        },

        /**
         * 重新生成助手回复（replace 模式）：按被替换消息重新发起对话。
         * 发起瞬间即置 sending，避免服务端返回前被重复点击。
         * @param {string} replaceMessageId 被替换的助手消息 id
         * @param {string} content 父用户消息的文本（重发的输入）
         * @returns {boolean} 是否已发出
         */
        replaceBranch(replaceMessageId, content) {
            if (!beginSend()) return false;
            sendChatRequest({
                mode: 'replace',
                replaceMessageId: replaceMessageId,
                content: content
            });
            return true;
        },

        /**
         * 编辑用户消息（edit 模式）：删除旧分支后按新内容重发。
         * 发起瞬间即置 sending，避免服务端返回前被重复点击。
         * @param {string} editMessageId 被编辑的用户消息 id
         * @param {string} content 编辑后的文本
         * @returns {boolean} 是否已发出
         */
        editMessage(editMessageId, content) {
            if (!beginSend()) return false;
            sendChatRequest({
                mode: 'edit',
                editMessageId: editMessageId,
                content: content
            });
            return true;
        },

        /** 重播某条消息的整轮音频（委托发送区播放） */
        playMessageAudio(msg) {
            ui.playMessageAudio(msg);
        },

        /* ================= 消息操作 ================= */

        /** 用真实工具结果替换对应的"执行中"占位消息（见文件顶部 replaceToolPlaceholder） */
        replaceToolPlaceholder,

        /** 立即应用缓冲的 chunk/done 帧（其它类型的帧处理前调用，保证顺序） */
        flushPendingChunks,

        /** 丢弃缓冲的 chunk/done 帧（断线/重连场景） */
        discardPendingChunks,

        /** 查找当前会话（或指定会话）最后一条在途流式 assistant 消息 */
        findStreamingMessage(sessionId) {
            const sid = sessionId || currentSessionId();
            return state.currentMessages.findLast(m => m.sessionId === sid
                && m.role === 'assistant' && m.id && String(m.id).startsWith('streaming_'));
        },

        /** 追加思维过程 / 正文 / 工具调用（chunk / done 帧） */
        appendChunk(streamingMessage, response) {
            // 出现实质产出（思考 / 正文 / 工具调用）才结束「连接中 / 思考中」状态。
            // 不能只看 messages 是否存在：空数组在 JS 里是真值，仅带 role 的握手帧会把状态在首帧就抹掉
            const produced = response.reasoningContent || response.text
                || (Array.isArray(response.messages)
                    && response.messages.some(m => m.toolCalls && m.toolCalls.length > 0));
            if (produced) {
                this.clearRequestState(streamingMessage.sessionId);
            }
            if (response.reasoningContent) {
                streamingMessage.reasoningContent += response.reasoningContent;
            }
            if (response.text) {
                const lastContent = getLast(streamingMessage.contents);
                if (lastContent) {
                    lastContent.content += response.text;
                }
            }
            if (response.messages && response.messages.length > 0) {
                for (let index = 0; index < response.messages.length; index++) {
                    const message = response.messages[index];
                    if (message.sessionId) streamingMessage.sessionId = message.sessionId;
                    if (message.parentId) streamingMessage.parentId = message.parentId;
                    if (message.name) streamingMessage.name = message.name;
                    if (message.role) streamingMessage.role = message.role;

                    if (message.toolCalls && message.toolCalls.length > 0) {
                        for (let i = 0; i < message.toolCalls.length; i++) {
                            const eventToolCall = message.toolCalls[i];
                            if (eventToolCall.id) {
                                state.currentToolCallId = eventToolCall.id;
                            }
                            let toolCall = streamingMessage.toolCalls.find(tc => tc.id === state.currentToolCallId);
                            if (!toolCall) {
                                toolCall = { id: state.currentToolCallId, name: '', arguments: '' };
                                streamingMessage.toolCalls.push(toolCall);
                            }
                            if (eventToolCall.name) {
                                toolCall.name = eventToolCall.name;
                            }
                            if (eventToolCall.arguments) {
                                toolCall.arguments += eventToolCall.arguments;
                            }
                        }
                    }
                }
            }
            // 标记内容已变化：消息区据此只重渲染该消息所在分组（v-memo 依赖）
            streamingMessage._v = (streamingMessage._v || 0) + 1;
        },

        /* ================= 流式帧数据处理 ================= */

        /**
         * 处理 REPLAY 帧：后端只回放「最近一条落库的助手消息之后」的在途事件，
         * 此前的已落库消息都在历史里（selectSession / 重连补拉已提供）。
         * 因此不再截断到上一条 user 重放整轮，只清掉上次连接残留的流式占位，
         * 等待随后的 START / chunk 帧在历史之上增量重建当前在途消息。
         * @returns {boolean} 是否命中当前会话（未命中则不应继续处理）
         */
        handleReplay(sessionId) {
            flushPendingChunks();
            if (currentSessionId() !== sessionId) {
                return false;
            }
            // 能收到 REPLAY_MESSAGE 说明后端还有在途事件（缓冲为空时根本不发这个帧），
            // 即本轮仍在进行：置流式标记，让发送键显示为「停止」，而不是灰掉的纸飞机。
            // 之前置 false 是因为旧缓冲一定含 START 帧会再置 true；缓冲按落库清空后 START 可能已被清掉。
            state.streamingMap[sessionId] = true;
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            return true;
        },

        /** 处理 START 帧：置流式标记、清残留占位、插入新的 assistant 占位 */
        handleStart(sessionId) {
            flushPendingChunks();
            ui.clearTts();
            // 新会话发送时在途标记挂在空串上，拿到真实 sessionId 后迁移过去，避免遗留锁
            if (state.sendingMap['']) {
                delete state.sendingMap[''];
                state.sendingMap[sendingKey(sessionId)] = true;
            }
            state.streamingMap[sessionId] = true;
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            const assistantMessage = getDefaultAssistantMessage(sessionId);
            if (!currentSessionId() || sessionId === currentSessionId()) {
                state.currentMessages.push(assistantMessage);
            }
        },

        /** 处理 REPLACE 帧：截断到指定消息（含）之前 */
        handleReplace(messageId) {
            flushPendingChunks();
            const index = state.currentMessages.findIndex(m => m.id === messageId);
            console.log('replace:', index);
            if (index !== -1) {
                state.currentMessages.splice(index);
            }
        },

        /** 处理 END 帧：清流式标记与残留占位，触发 Mermaid 渲染 */
        handleEnd(sessionId) {
            // 先把缓冲的文本全部落盘，避免最后一帧 chunk 被结束帧丢掉
            flushPendingChunks();
            // 本轮结束：解除该会话的请求在途锁（出错/断线时另有 handleError/clearSending 兜底）
            clearSendingKey(sessionId);
            this.clearRequestState(sessionId);
            state.streamingMap[sessionId] = false;
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            Vue.nextTick(() => {
                ui.renderMermaid();
            });
        },

        /** 处理 TOOL_CALL_FINISH 帧：推进流式占位到下一轮 AI 回复 */
        handleToolCallFinish(sessionId) {
            flushPendingChunks();
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            const assistantMessage = getDefaultAssistantMessage(sessionId);
            if (sessionId === currentSessionId()) {
                state.currentMessages.push(assistantMessage);
                state.currentToolCallId = null;
            }
        },

        /** 处理 init_user 帧：确认用户消息、校正编辑重发、补会话引用 */
        async handleInitUser(response) {
            // 重放场景：用户消息通常已随历史加载进来，按 id 去重。
            // 直接跳过，避免重复插入，也避免误触发清空输入框等副作用。
            const incoming = response.messages && response.messages[0];
            if (incoming && incoming.id
                    && state.currentMessages.some(m => m.id === incoming.id)) {
                return;
            }
            // 编辑/重发场景：按 parentId 定位旧用户消息并掐掉它及之后的所有内容
            let cutIndex = -1;
            for (const message of response.messages) {
                const idx = state.currentMessages.findLastIndex(m => m.role === 'user' && m.parentId === message.parentId);
                if (idx !== -1) {
                    cutIndex = idx;
                    break;
                }
            }
            if (cutIndex !== -1) {
                state.currentMessages.splice(cutIndex);
            }
            state.currentMessages.push(...response.messages);

            await this.refreshSessions();
            if (!currentSessionId()) {
                state.currentSession = this.getSessionById(response.sessionId) || { id: response.sessionId };
            }

            // 服务端已确认收到消息，此时清空输入框和已上传文件
            ui.clearSendArea();

            ui.scrollToBottom(true);
            Vue.nextTick(() => {
                ui.renderMermaid();
            });
        },

        /** 处理 tool_execution 帧：插入黄色"执行中"占位 */
        handleToolExecution(response) {
            if (response.sessionId === currentSessionId()
                    && response.messages && response.messages.length > 0) {
                const placeholder = response.messages[0];
                // 重放场景：历史里可能已有该工具的落库消息，避免重复插入占位
                if (placeholder.toolCallId && state.currentMessages.some(m =>
                        m.role === 'tool' && m.toolCallId === placeholder.toolCallId)) {
                    return;
                }
                placeholder.extension = placeholder.extension || {};
                placeholder.extension.status = 'executing';
                state.currentMessages.push(placeholder);
                ui.scrollToBottom();
            }
        },

        /** 处理 tool_response 帧：替换执行中占位 */
        handleToolResponse(response) {
            if (response.sessionId === currentSessionId()) {
                for (const m of response.messages) {
                    replaceToolPlaceholder(m);
                }
            }
        },

        /** 处理 init_tool 帧：用真实 DB id 校准占位/结果消息的假 id */
        handleInitTool(response) {
            if (response.sessionId === currentSessionId() && response.messages) {
                for (const m of response.messages) {
                    const idx = state.currentMessages.findIndex(x =>
                        x.role === 'tool' && x.toolCallId === m.toolCallId);
                    if (idx !== -1) {
                        const localMsg = state.currentMessages[idx];
                        if (m.id) localMsg.id = m.id;
                        if (m.parentId) localMsg.parentId = m.parentId;
                        if (m.createTime) localMsg.createTime = m.createTime;
                        localMsg._v = (localMsg._v || 0) + 1;
                    }
                }
            }
        },

        /** 处理 init_assistant 帧：只同步元数据，保留已累积内容 */
        handleInitAssistant(streamingMessage, response) {
            const serverMsg = response.messages && response.messages[0];
            if (!streamingMessage) {
                // 重放兜底：历史里可能没有这条已落库的助手消息（断线窗口内落库），补插
                if (serverMsg && serverMsg.id
                        && response.sessionId === currentSessionId()
                        && !state.currentMessages.some(m => m.id === serverMsg.id)) {
                    state.currentMessages.push(serverMsg);
                }
                return;
            }
            if (!serverMsg) return;
            if (serverMsg.id) streamingMessage.id = serverMsg.id;
            if (serverMsg.sessionId) streamingMessage.sessionId = serverMsg.sessionId;
            if (serverMsg.parentId) streamingMessage.parentId = serverMsg.parentId;
            if (serverMsg.name) streamingMessage.name = serverMsg.name;
            if (serverMsg.role) streamingMessage.role = serverMsg.role;
            if (serverMsg.createTime) streamingMessage.createTime = serverMsg.createTime;
            if (serverMsg.siblingCount != null) streamingMessage.siblingCount = serverMsg.siblingCount;
            if (serverMsg.siblingIndex != null) streamingMessage.siblingIndex = serverMsg.siblingIndex;
            // 同步落库消息的 extension（如 TTS 整轮完整音频 ttsAudio，供 🔊 重播）
            if (serverMsg.extension) {
                streamingMessage.extension = Object.assign({}, streamingMessage.extension || {}, serverMsg.extension);
            }
            // 斜杠指令等场景：没有 chunk 流，文字直接附在 init_assistant 里
            if (response.text) {
                const lastContent = getLast(streamingMessage.contents);
                if (lastContent && !lastContent.content) {
                    lastContent.content = response.text;
                }
            }
            streamingMessage._v = (streamingMessage._v || 0) + 1;
        },

        /** 处理 error 帧：复位发送态、清理流式消息与标记、补拉历史、返回错误文本 */
        handleError(response) {
            const errSessionId = response.sessionId || currentSessionId();
            clearSendingKey(errSessionId);
            this.clearRequestState(errSessionId);
            if (errSessionId) {
                state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, errSessionId));
            }
            if (errSessionId) {
                state.streamingMap[errSessionId] = false;
            }
            // 本轮失败时界面可能已按 REPLACE 帧截断过（旧助手消息被删掉），
            // 而服务端此刻已把那条分支回滚为活跃 —— 必须重拉历史把消息补回来。
            // 成功路径不会走到这里，所以正常轮次只多一次 filter，没有额外请求
            this.refreshHistoryOnError(errSessionId);
            const errText = (response.messages && response.messages.length > 0)
                ? response.messages[0].contents?.map(c => c.content).join('')
                : 'AI 服务返回了一个错误，请稍后重试';
            return errText || '未知错误';
        },

        /**
         * 出错后补拉当前会话历史：服务端在错误路径上会回滚 replace 停用的旧分支，
         * 前端手里那份被 ###REPLACE### 截断过的列表已经过期，不重拉就看不到恢复后的分支。
         * <p>
         * 仅当错误帧指向当前会话时才拉（别的会话的错误由它自己切过去时再拉）；
         * 请求返回时会话可能已被切走，过期结果直接丢弃。
         * @param {string} sessionId 出错帧携带的会话 id
         */
        async refreshHistoryOnError(sessionId) {
            if (!sessionId || sessionId !== currentSessionId()) {
                return;
            }
            try {
                const result = await API.message.getHistory(sessionId);
                if (currentSessionId() !== sessionId) return;
                if (result && result.status === 200) {
                    state.currentMessages = result.data;
                    ui.scrollToBottom(true);
                    Vue.nextTick(() => {
                        ui.renderMermaid();
                    });
                }
            } catch (e) {
                console.error('出错后补拉历史失败:', e);
            }
        },

        /** 处理 TTS_AUDIO 帧：把音频注入流式消息的 extension（保序）并实时入队播放 */
        handleTtsAudio(ttsFrame) {
            if (!ttsFrame || ttsFrame.sessionId !== currentSessionId() || !ui.isTtsEnabled()) {
                return;
            }
            const ttsStreamingMessage = state.currentMessages.findLast(m => m.sessionId === ttsFrame.sessionId
                && m.role === 'assistant' && m.id && String(m.id).startsWith('streaming_'));
            if (ttsStreamingMessage) {
                ttsStreamingMessage.extension = ttsStreamingMessage.extension || {};
                if (!ttsStreamingMessage.extension.audios) {
                    ttsStreamingMessage.extension.audios = [];
                }
                ttsStreamingMessage.extension.audios.push(ttsFrame.audio);
                ttsStreamingMessage._v = (ttsStreamingMessage._v || 0) + 1;
            }
            ui.enqueueTts(ttsFrame.audio);
        },

        /**
         * 上下文开始压缩（服务端在调用模型总结旧对话前推送）：
         * 进入「压缩中」状态，移除空的流式占位，由消息区渲染压缩卡片。
         */
        handleContextCompressing(sessionId) {
            flushPendingChunks();
            if (sessionId !== currentSessionId()) {
                return;
            }
            state.compressMap[sessionId] = 'running';
            // 此时还未向模型发起本轮请求，占位为空：先撤掉，压缩完成后由 handleContextCompressed 补回
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            ui.scrollToBottom(true);
            Vue.nextTick(() => {
                ui.renderMermaid();
            });
        },

        /**
         * 上下文已压缩（服务端删旧消息、摘要并入 root 用户消息后推送）：
         * 重拉该会话最新历史；若本轮仍在流式中，补一个新的空占位承接后续 chunk。
         */
        async handleContextCompressed(sessionId) {
            flushPendingChunks();
            if (sessionId !== currentSessionId()) {
                return;
            }
            // 同步置为完成态，避免紧随其后的 CONTEXT_COMPRESS_END 误判为「压缩未生效」
            state.compressMap[sessionId] = 'done';
            const wasStreaming = !!state.streamingMap[sessionId];
            try {
                const result = await API.message.getHistory(sessionId);
                if (result.status === 200) {
                    state.currentMessages = result.data;
                }
            } catch (e) {
                console.error('上下文压缩后刷新历史失败:', e);
            }
            if (wasStreaming) {
                state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
                state.currentMessages.push(getDefaultAssistantMessage(sessionId));
                state.currentToolCallId = null;
            }
            ui.scrollToBottom(true);
            Vue.nextTick(() => {
                ui.renderMermaid();
            });
            // 成功态短暂展示后自动收起
            setTimeout(() => {
                if (state.compressMap[sessionId] === 'done') {
                    delete state.compressMap[sessionId];
                }
            }, 3200);
        },

        /**
         * 压缩结束但未生效（总结失败/结果为空/异常）：关闭压缩卡片，
         * 若本轮仍在流式中则补回空占位承接后续 chunk。
         */
        handleContextCompressEnd(sessionId) {
            flushPendingChunks();
            const wasRunning = state.compressMap[sessionId] === 'running';
            if (state.compressMap[sessionId]) {
                delete state.compressMap[sessionId];
            }
            if (sessionId !== currentSessionId()) {
                return;
            }
            // 仅「压缩未生效」时需要补回流式落点；成功压缩已由 handleContextCompressed 处理
            if (wasRunning && state.streamingMap[sessionId]) {
                state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
                state.currentMessages.push(getDefaultAssistantMessage(sessionId));
                state.currentToolCallId = null;
                ui.scrollToBottom(true);
            }
        },

        /**
         * 本轮请求正在与模型建连（服务端在 adapter.connect 之前推送）：
         * 在途气泡显示「连接中」，等 THINKING 或首个产出帧接手。
         */
        handleRequestConnecting(sessionId) {
            // 新会话首轮的窗口期：init_user 里 refreshSessions 是 await 的，currentSession 尚未落定时本信号可能先到。
            // 此时没有「当前会话」可比，直接放行——状态按真实 sessionId 存，currentSession 落定后自然就读出来了
            if (currentSessionId() && sessionId !== currentSessionId()) {
                return;
            }
            state.requestStateMap[sessionId] = 'connecting';
        },

        /**
         * 连接已建立、模型尚未产出第一条内容（服务端收到响应头之后、首帧数据之前推送）：
         * 在途气泡把「连接中」换成「思考中」。
         */
        handleRequestThinking(sessionId) {
            // 同 CONNECTING：新会话首轮可能早于 currentSession 落定到达，无当前会话时放行
            if (currentSessionId() && sessionId !== currentSessionId()) {
                return;
            }
            state.requestStateMap[sessionId] = 'thinking';
        },

        /** 清除某会话的本轮请求状态（首个产出帧到达 / 本轮结束 / 出错 / 断线时调用） */
        clearRequestState(sessionId) {
            delete state.requestStateMap[sessionId];
        },

        /** 断线重连：清空全部流式标记并清掉残留占位 */
        resetStreamingOnReconnect() {
            discardPendingChunks();
            state.streamingMap = {};
            state.compressMap = {};
            state.requestStateMap = {};
            state.currentMessages = state.currentMessages.filter(m =>
                !(m.role === 'assistant' && m.id && String(m.id).startsWith('streaming_')));
        },

        /** 断线等场景下的发送态复位：清空所有会话的在途标记 */
        clearSending() {
            state.sendingMap = {};
        },

        /* ================= WS 帧路由 ================= */

        /**
         * 连接建立/重连后调用：清残留流式占位 → 补拉历史 → 请求当前会话续传。
         * 断线期间落库的消息不在总线缓冲里（落助手消息即清空缓冲），必须靠历史补齐；
         * 随后总线只回放「最后一条落库助手消息之后」的在途事件，前端在历史之上增量重建即可。
         */
        async onSocketConnected() {
            const sessionId = currentSessionId();
            if (!sessionId) return;
            this.resetStreamingOnReconnect();
            try {
                const result = await API.message.getHistory(sessionId);
                if (currentSessionId() !== sessionId) return;
                if (result && result.status === 200) {
                    state.currentMessages = result.data;
                }
            } catch (e) {
                console.error('重连补拉历史失败:', e);
            }
            // 补拉期间可能已切走会话，过期结果直接丢弃
            if (currentSessionId() !== sessionId) return;
            const ws = WsBus.getSocket();
            if (ws && ws.readyState === 1) {
                ws.send("###REQUIRE_REPLAY_MESSAGE###" + sessionId);
            }
        },

        /**
         * 处理未被精确订阅的帧（JSON 状态帧）：原 index.handleStreamChunk 的剩余逻辑。
         * 各 ###SIGNAL### 帧已由 registerWsBridge 精确订阅，不会进入这里。
         */
        async handleWsMessage(raw) {
            if (typeof raw !== 'string' || raw.startsWith(':')) {
                return;
            }
            if (raw.startsWith('###')) {
                console.warn('未知的信号:' + raw);
                return;
            }
            let response;
            try {
                response = JSON.parse(raw);
            } catch (e) {
                return;
            }

            // 文本增量帧先缓冲，攒到下一次 rAF 统一应用，避免逐帧整列重渲染
            if (response.status === 'chunk' || response.status === 'done') {
                queueChunk(response);
                return;
            }

            // 其它帧必须建立在已应用的文本之上，先把缓冲冲刷干净（保证先后顺序）
            flushPendingChunks();

            switch (response.status) {
                case 'init_user': {
                    console.log('init_user', response);
                    await this.handleInitUser(response);
                    break;
                }
                case 'tool_execution': {
                    // 工具开始执行：插入黄色"执行中"占位消息
                    this.handleToolExecution(response);
                    break;
                }
                case 'tool_response': {
                    // 单个工具执行完成 → 用真实结果替换同名"执行中"占位
                    this.handleToolResponse(response);
                    break;
                }
                case 'init_tool': {
                    // 工具消息已落库，携带真实 DB id：校准占位/结果消息的假 id
                    this.handleInitTool(response);
                    break;
                }
                case 'init_assistant': {
                    this.handleInitAssistant(this.findStreamingMessage(response.sessionId), response);
                    break;
                }
                case 'error': {
                    console.error('AI 对话错误:', response);
                    const errText = this.handleError(response);
                    ElementPlus.ElMessage.error(errText);
                    break;
                }
            }
        }
    };

    /**
     * 让 store 直接接管 WebSocket 帧路由：加载时订阅各 ###SIGNAL### 帧，
     * 并订阅 '*' 兜底 JSON 状态帧。
     * 组件已精确订阅的信号（TOOL_ASK / TOOL_QUESTION / AGENT_LOG / KNOWLEDGE_HIT）
     * 按 WsBus 规则不会进入 '*'，因此互不冲突。
     */
    (function registerWsBridge() {
        if (typeof WsBus === 'undefined' || store._wsBridgeReady) return;
        store._wsBridgeReady = true;

        WsBus.on('REPLAY_MESSAGE', payload => store.handleReplay(payload));
        WsBus.on('START', payload => store.handleStart(payload));
        WsBus.on('REPLACE', payload => store.handleReplace(payload));
        WsBus.on('END', payload => store.handleEnd(payload));
        WsBus.on('TOOL_CALL_FINISH', payload => store.handleToolCallFinish(payload));
        WsBus.on('CONTEXT_COMPRESSING', payload => store.handleContextCompressing(payload));
        WsBus.on('CONTEXT_COMPRESSED', payload => store.handleContextCompressed(payload));
        WsBus.on('CONTEXT_COMPRESS_END', payload => store.handleContextCompressEnd(payload));
        WsBus.on('REQUEST_CONNECTING', payload => store.handleRequestConnecting(payload));
        WsBus.on('REQUEST_THINKING', payload => store.handleRequestThinking(payload));
        WsBus.on('UPDATE_SESSION', payload => {
            console.log('update session', payload);
            try {
                store.upsertSession(JSON.parse(payload));
            } catch (e) {
                console.error('解析 UPDATE_SESSION 失败:', e);
            }
        });
        WsBus.on('TTS_AUDIO', payload => {
            try {
                store.handleTtsAudio(JSON.parse(payload));
            } catch (e) {
                console.error('解析 TTS_AUDIO 失败:', e);
            }
        });
        WsBus.on('*', raw => store.handleWsMessage(raw));
        // 连接生命周期：由 chat-connection 经 WsBus.setSocket / clearSocket 广播
        WsBus.on('ws:connected', () => store.onSocketConnected());
        WsBus.on('ws:disconnected', () => {
            store.discardPendingChunks();
            store.clearSending();
        });
    })();

    return store;
})();
