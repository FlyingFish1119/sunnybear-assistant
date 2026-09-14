/**
 * SessionStore — 会话与消息状态仓库
 *
 * 把原先散落在页面主组件的「当前会话 / 消息列表 / 流式状态」及其数据操作
 * 整体内聚到这里，通过 app.provide('sessionStore', SessionStore) 注入后代组件，
 * 组件用 inject 直接读写，不必再由父级层层传 props。
 *
 * 负责：
 *   - 状态：currentSession / currentMessages / streamingMap / currentToolCallId /
 *           sessionSelectLoading / sending
 *   - 派生：currentSessionId / isStreaming / isNewSession
 *   - 数据方法：会话切换、消息增删、流式标记、工具占位替换、各类 WS 帧的数据处理
 *
 * 不负责（UI 副作用）：滚动、Mermaid 渲染、TTS 播放、侧边栏刷新、关闭抽屉等。
 * 这些通过 ui 钩子对象交由页面主组件实现（registerUi(hooks)），store 内调用，
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

    /** UI 副作用钩子（由页面主组件通过 registerUi 注入，默认空实现） */
    const ui = {
        scrollToBottom: function () {},
        renderMermaid: function () {},
        knowledgeFlash: function () {},
        enqueueTts: function () {},
        playMessageAudio: function () {},
        clearTts: function () {},
        isTtsEnabled: function () { return false; },
        clearMdCache: function () {},
        closeSidebar: function () {},
        refreshSidebar: function () { return Promise.resolve(); },
        getSessionRef: function (session) { return session; },
        clearSendArea: function () {},
        clearAgentLogs: function () {}
    };

    const state = reactive({
        /** 当前正在处理的 session（对象引用，尽量指向侧边栏 sessions 里的同一对象） */
        currentSession: {},
        /** 当前正在展示的 messages */
        currentMessages: [],
        /** 流式标记表：key = sessionId，各会话只看自己的标记，互不影响 */
        streamingMap: {},
        /** 当前正在处理的 tool call id */
        currentToolCallId: null,
        /** 会话历史加载中 */
        sessionSelectLoading: false,
        /** 已发送但尚未收到服务端 init_user 确认 —— 防止窗口期内重复提交 */
        sending: false
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

    /** 标记开始发送（等待服务端 init_user 确认期间禁止重复提交），返回是否成功占位 */
    function beginSend() {
        if (state.sending) return false;
        state.sending = true;
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

    /**
     * 用真实工具结果替换对应的"执行中"占位消息（按 toolCallId 精确匹配）。
     * 找不到占位时（如页面刷新后）直接追加。
     * @param {object} resultMsg 工具结果消息
     */
    function replaceToolPlaceholder(resultMsg) {
        if (!resultMsg || !resultMsg.toolCallId) return;
        const idx = state.currentMessages.findIndex(m =>
            m.role === 'tool'
            && m.extension && m.extension.status === 'executing'
            && m.toolCallId === resultMsg.toolCallId);
        if (idx !== -1) {
            state.currentMessages.splice(idx, 1, resultMsg);
        } else {
            state.currentMessages.push(resultMsg);
        }
        ui.scrollToBottom();
    }

    return {
        /** 注入后代组件的 key 名（供文档/常量引用） */
        provideKey: 'sessionStore',

        /** 响应式状态（组件可直接读写；state.currentSession 等） */
        state: state,

        /* ================= 派生属性 ================= */

        get currentSession() { return state.currentSession; },
        get currentMessages() { return state.currentMessages; },
        get currentSessionId() { return currentSessionId(); },
        get isNewSession() { return currentSessionId() === undefined; },
        get isStreaming() { return !!state.streamingMap[currentSessionId()]; },
        get sessionSelectLoading() { return state.sessionSelectLoading; },
        get sending() { return state.sending; },

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
            ui.closeSidebar();
            ui.clearMdCache();
            ui.clearAgentLogs();
            ui.clearTts();
            state.sessionSelectLoading = true;
            state.currentSession = session;
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
            ui.closeSidebar();
            ui.clearMdCache();
            ui.clearAgentLogs();
            state.currentSession = {};
            state.currentMessages = [];
        },

        /**
         * 侧边栏通知某会话已被删除：若删的是当前会话则清空视图。
         * @param {object} session 被删除的会话对象
         */
        onDeleteSession(session) {
            if (state.currentSession === session) {
                state.currentSession = {};
                state.currentMessages = [];
            }
        },

        /**
         * 侧边栏 upsert 后同步当前会话引用（###UPDATE_SESSION###）。
         * @param {object} session 服务端下发的会话对象
         * @returns {object} 侧边栏内部数组里的会话对象引用
         */
        upsertSession(session) {
            const ref = ui.getSessionRef(session);
            if (session.id === currentSessionId()) {
                state.currentSession = ref;
            }
            return ref;
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
         * @param {string} replaceMessageId 被替换的助手消息 id
         * @param {string} content 父用户消息的文本（重发的输入）
         */
        replaceBranch(replaceMessageId, content) {
            sendChatRequest({
                mode: 'replace',
                replaceMessageId: replaceMessageId,
                content: content
            });
        },

        /**
         * 编辑用户消息（edit 模式）：删除旧分支后按新内容重发。
         * @param {string} editMessageId 被编辑的用户消息 id
         * @param {string} content 编辑后的文本
         */
        editMessage(editMessageId, content) {
            sendChatRequest({
                mode: 'edit',
                editMessageId: editMessageId,
                content: content
            });
        },

        /** 重播某条消息的整轮音频（委托发送区播放） */
        playMessageAudio(msg) {
            ui.playMessageAudio(msg);
        },

        /* ================= 消息操作 ================= */

        /** 用真实工具结果替换对应的"执行中"占位消息（见文件顶部 replaceToolPlaceholder） */
        replaceToolPlaceholder,

        /** 查找当前会话（或指定会话）最后一条在途流式 assistant 消息 */
        findStreamingMessage(sessionId) {
            const sid = sessionId || currentSessionId();
            return state.currentMessages.findLast(m => m.sessionId === sid
                && m.role === 'assistant' && m.id && String(m.id).startsWith('streaming_'));
        },

        /** 追加思维过程 / 正文 / 工具调用（chunk / done 帧） */
        appendChunk(streamingMessage, response) {
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
        },

        /* ================= 流式帧数据处理 ================= */

        /**
         * 处理 REPLAY 帧：截断到最后一条 user 消息（连同残留的流式占位）。
         * @returns {boolean} 是否命中当前会话（未命中则不应继续处理）
         */
        handleReplay(sessionId) {
            if (currentSessionId() !== sessionId) {
                return false;
            }
            state.streamingMap[sessionId] = false;
            const lastUserIndex = state.currentMessages.findLastIndex(m => m.role === 'user');
            if (lastUserIndex !== -1) {
                state.currentMessages.splice(lastUserIndex);
            }
            return true;
        },

        /** 处理 START 帧：置流式标记、清残留占位、插入新的 assistant 占位 */
        handleStart(sessionId) {
            ui.clearTts();
            state.streamingMap[sessionId] = true;
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            const assistantMessage = getDefaultAssistantMessage(sessionId);
            if (!currentSessionId() || sessionId === currentSessionId()) {
                state.currentMessages.push(assistantMessage);
            }
        },

        /** 处理 REPLACE 帧：截断到指定消息（含）之前 */
        handleReplace(messageId) {
            const index = state.currentMessages.findIndex(m => m.id === messageId);
            console.log('replace:', index);
            if (index !== -1) {
                state.currentMessages.splice(index);
            }
        },

        /** 处理 END 帧：清流式标记与残留占位，触发 Mermaid 渲染 */
        handleEnd(sessionId) {
            state.streamingMap[sessionId] = false;
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            Vue.nextTick(() => {
                ui.renderMermaid();
            });
        },

        /** 处理 TOOL_CALL_FINISH 帧：推进流式占位到下一轮 AI 回复 */
        handleToolCallFinish(sessionId) {
            state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, sessionId));
            const assistantMessage = getDefaultAssistantMessage(sessionId);
            if (sessionId === currentSessionId()) {
                state.currentMessages.push(assistantMessage);
                state.currentToolCallId = null;
            }
        },

        /** 处理 init_user 帧：确认用户消息、校正编辑重发、补会话引用 */
        async handleInitUser(response) {
            state.sending = false;

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

            await ui.refreshSidebar();
            if (!currentSessionId()) {
                state.currentSession = ui.getSessionRef({ id: response.sessionId });
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
                    }
                }
            }
        },

        /** 处理 init_assistant 帧：只同步元数据，保留已累积内容 */
        handleInitAssistant(streamingMessage, response) {
            if (!streamingMessage) {
                return;
            }
            const serverMsg = response.messages[0];
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
        },

        /** 处理 error 帧：复位发送态、清理流式消息与标记、返回错误文本 */
        handleError(response) {
            state.sending = false;
            const errSessionId = response.sessionId || currentSessionId();
            if (errSessionId) {
                state.currentMessages = state.currentMessages.filter(m => !isStreamingPlaceholder(m, errSessionId));
            }
            if (errSessionId) {
                state.streamingMap[errSessionId] = false;
            }
            const errText = (response.messages && response.messages.length > 0)
                ? response.messages[0].contents?.map(c => c.content).join('')
                : 'AI 服务返回了一个错误，请稍后重试';
            return errText || '未知错误';
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
            }
            ui.enqueueTts(ttsFrame.audio);
        },

        /** 断线重连：清空全部流式标记并清掉残留占位 */
        resetStreamingOnReconnect() {
            state.streamingMap = {};
            state.currentMessages = state.currentMessages.filter(m =>
                !(m.role === 'assistant' && m.id && String(m.id).startsWith('streaming_')));
        },

        /** 中止流式时的发送态复位 */
        clearSending() {
            state.sending = false;
        }
    };
})();
