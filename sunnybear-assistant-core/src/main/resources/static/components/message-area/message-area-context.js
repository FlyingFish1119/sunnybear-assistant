/**
 * 消息区共享上下文：骨架与消息子组件之间的公共状态 + 动作。
 *
 * 骨架（message-area.js）只负责分组循环与导轨之外的装配；所有消息级共享状态
 * （编辑态、折叠态、头像兜底、主题设置）都收在 context 里，provide/inject 下发。
 *
 * 职责边界：context 只保留真正跨组件共享的内容——共享状态读写（编辑态 / 折叠态 /
 * 头像兜底）、纯工具（bodyText / 头像解析）、以及需要编排的跨组件动作（switchBranch）。
 * 单条消息自身的动作（编辑 / 复制 / 删除 / 重新生成 / 语音）由各消息组件自持，
 * 直接调用 store / API，不再经 context 中转。
 *
 * 依赖全局：Vue、ElementPlus、sessionStore（创建时注入）、$md、$fileUrl、ColorUtils。
 */

/** 换行符常量：拼装工具调用参数的 markdown 时用，避免在字符串里写转义符 */
const MSG_NL = String.fromCharCode(10);

/**
 * 消息区插件注册表（全局单例）。
 *
 * 插件在任意时机（脚本加载后即可，无需等组件挂载）调用 register 声明自己的扩展
 * 组件；MessageArea 创建 context 时会把已登记的条目同步进 ctx，之后按锚点渲染。
 *
 * 锚点（slot anchor）——每个锚点对应消息区的一个固定插入位置：
 *   'message-list-top'   消息列表顶部（新对话开场等整块内容）
 *   'message-bubble'     每条消息气泡末尾（组件自行按 msg 判断是否渲染）
 *   'assistant-actions'  助手消息操作按钮区末尾（等价于 message-bubble 的助手场景）
 *
 * 组件会收到 prop: { msg }（列表顶部锚点 msg 为 null），并可 inject 共享上下文。
 */
const MessageAreaPlugins = (function () {
    /** 锚点名 → 槽条目数组（{ component, order }） */
    const slotsByAnchor = Object.create(null);

    /** 取（或初始化）某锚点的槽数组 */
    function bucket(anchor) {
        if (!slotsByAnchor[anchor]) slotsByAnchor[anchor] = [];
        return slotsByAnchor[anchor];
    }

    /**
     * 注册一个消息区扩展组件。
     * @param {string} anchor - 锚点名（见文件顶部说明）
     * @param {object} component - Vue 组件选项对象（会收到 prop: msg）
     * @param {number} [order=0] - 排序权重，越小越靠前
     */
    function registerSlot(anchor, component, order) {
        if (!anchor || !component) return;
        const arr = bucket(anchor);
        arr.push({ component: component, order: order || 0 });
        arr.sort((a, b) => a.order - b.order);
    }

    /** 注册一个助手操作区扩展（兼容旧 API，等价于 'assistant-actions' 锚点） */
    function registerAssistantActionSlot(component, order) {
        registerSlot('assistant-actions', component, order);
    }

    return {
        registerSlot: registerSlot,
        registerAssistantActionSlot: registerAssistantActionSlot,
        /** 供 context 创建时取某锚点已登记槽的拷贝 */
        snapshot: function (anchor) {
            return (slotsByAnchor[anchor] || []).slice();
        },
        /** 兼容旧名：等价于 snapshot('assistant-actions') */
        snapshotAssistantActionSlots: function () {
            return (slotsByAnchor['assistant-actions'] || []).slice();
        }
    };
})();


/**
 * 消息渲染缓存：按「对象 + 源文本」记忆 Markdown 结果。
 * 历史消息内容不变，组件重渲染时直接复用，避免每次 chunk 都重新 marked.parse。
 * 用 WeakMap（不挂到 reactive 对象上，避免污染响应式并额外触发更新）。
 */
const _msgHtmlCache = new WeakMap();

/** 含 mermaid 围栏的文本不走对象缓存：其 HTML 会随异步出图被全局缓存失效，需每次重算 */
const MSG_MERMAID_FENCE_RE = /(^|\n)\s{0,3}(`{3,}|~{3,})mermaid/;

/**
 * 按「对象 + 字段槽 + 源文本」记忆渲染结果。同一个对象上可能并存多个渲染字段
 * （如消息的思考过程与工具调用参数），必须分槽存储，否则会互相覆盖、每帧重算。
 */
function memoMsgHtml(obj, slot, text, render) {
    // mermaid 渲染完成后 $md 会清全局缓存并通知重渲染，对象缓存会挡住这次替换，故直接走 $md
    if (text && MSG_MERMAID_FENCE_RE.test(text)) {
        return render(text);
    }
    let store = _msgHtmlCache.get(obj);
    if (!store) {
        store = {};
        _msgHtmlCache.set(obj, store);
    }
    const hit = store[slot];
    if (hit && hit.src === text) return hit.html;
    const html = render(text);
    store[slot] = { src: text, html: html };
    return html;
}

/**
 * 创建消息区共享上下文（reactive 对象 + 动作集）。
 * @param {object} sessionStore - 会话/消息仓库
 * @returns {object} context：含共享状态与 actions 动作集
 */
function createMessageAreaContext(sessionStore) {
    const ctx = Vue.reactive({
        // 主题色 / 头像设置：由骨架的 props 同步进来
        mainColor: 'lightsalmon',
        userSettings: { avatar: '', username: '', background: '', opacity: 0.3 },
        assistantSettings: { avatar: '', assistantName: '' },
        // 头像加载失败兜底：失败后回退到默认首字母头像
        userAvatarError: false,
        assistantAvatarError: false,
        // 消息编辑状态
        currentEditId: null,
        editDraft: '',
        editTargetRole: null,
        // 折叠状态：key = msgId_section, value = true(折叠)/false(展开)
        collapsedState: {},
        // mermaid 异步出图完成时需要让子组件重渲染（渲染时会读取该计数）
        mermaidNonce: 0,
        // 插件渲染槽：锚点名 → 槽条目数组（元素形如 { component, order }）。
        // 初始即纳入启动阶段通过 MessageAreaPlugins 登记的插件。
        slots: {
            'message-list-top': MessageAreaPlugins.snapshot('message-list-top'),
            'message-bubble': MessageAreaPlugins.snapshot('message-bubble'),
            'assistant-actions': MessageAreaPlugins.snapshot('assistant-actions')
        }
    });

    const api = {
        sessionStore: sessionStore,

        /**
         * 取消息正文（contents[0] 的文本）。
         * 语义契约：正文恒为首块且可编辑；其后的文本块与附件只展示，不参与编辑，
         * 因此编辑草稿、重发等"正文操作"一律只认首块，不拼接其它文本块。
         * @param {object} msg - 消息对象
         * @returns {string} 正文文本；首块不是文本时返回空串
         */
        bodyText(msg) {
            const first = msg && msg.contents && msg.contents[0];
            return first && first.type === 'text' ? (first.content || '') : '';
        },

        /**
         * 是否为当前正在流式输出的助手消息（为 true 时正文 / 思考过程 / 工具参数改走按块渲染）
         * @param {object} msg - 消息对象
         * @returns {boolean}
         */
        isStreamingMsg(msg) {
            return sessionStore.isStreaming && msg.role === 'assistant'
                && msg.id && String(msg.id).startsWith('streaming_');
        },

        /**
         * 判断指定消息是否处于"思考中"（流式且只有思维链）
         * @param {object} msg - 消息对象
         * @returns {boolean}
         */
        isThinkingMsg(msg) {
            if (!sessionStore.isStreaming || !(msg.role === 'assistant' && msg.id && msg.id.startsWith('streaming_'))) return false;
            const hasReasoning = msg.reasoningContent != null && msg.reasoningContent.length > 0;
            // 正文是否开始看首块（contents[0]）：末尾可能是附件，看末块会永远判成"正文还没开始"
            const bodyStarted = api.bodyText(msg).length > 0;
            return hasReasoning && !bodyStarted;
        },

        /** 获取消息对应的头像 URL（$fileUrl.proxy 已处理 data:/http(s) 与本地路径代理） */
        getMessageAvatar(msg) {
            if (msg.role === 'user' && ctx.userAvatarError) return '';
            if (msg.role === 'assistant' && ctx.assistantAvatarError) return '';
            const avatar = msg.role === 'user'
                ? ctx.userSettings.avatar
                : ctx.assistantSettings.avatar;
            return FileUrlUtils.proxy(avatar);
        },

        /** 掷取消息头像的默认首字母 */
        getAvatarInitial(msg) {
            if (msg.role === 'user') {
                return (ctx.userSettings.username || 'U').charAt(0);
            }
            return (ctx.assistantSettings.assistantName || 'A').charAt(0);
        },

        /* ---------- 编辑态共享读写（动作由各消息组件自持） ---------- */

        /** 清空编辑态（供消息组件确认 / 取消编辑后调用） */
        clearEditState() {
            ctx.currentEditId = null;
            ctx.editDraft = '';
            ctx.editTargetRole = null;
        },

        /** 聚焦编辑框（双重 rAF 确保 DOM 就绪且 lucide 刷新不影响 focus） */
        focusEditTextarea() {
            Vue.nextTick(() => {
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        const el = document.querySelector('.message-edit-textarea');
                        if (el) el.focus();
                    });
                });
            });
        },

        /* ---------- 分支切换（编排型：切换后需刷新整个会话） ---------- */

        /**
         * 左右切换兄弟分支
         * @param {object} msg - 当前消息对象
         * @param {string} direction - 'left' 或 'right'
         */
        async switchBranch(msg, direction) {
            try {
                const result = await API.message.switchBranch(msg.id, direction);
                if (result.status === 200) {
                    if (sessionStore.currentSessionId) {
                        await sessionStore.selectSession(sessionStore.state.currentSession);
                    }
                } else {
                    ElementPlus.ElMessage.error(result.message || '切换分支失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('切换分支失败:', error);
            }
        },

        /* ---------- 通用工具 ---------- */

        /**
         * 写入剪贴板，优先使用现代 API，失败则降级到临时 textarea
         * @param {string} text - 要复制的文本
         */
        async writeToClipboard(text) {
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(text);
                    ElementPlus.ElMessage.success('已复制到剪贴板');
                    return;
                }
            } catch (e) {
                console.warn('navigator.clipboard.writeText 失败，尝试降级方案', e);
            }
            try {
                const textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.style.position = 'fixed';
                textarea.style.left = '-9999px';
                textarea.style.top = '-9999px';
                document.body.appendChild(textarea);
                textarea.focus();
                textarea.select();
                const success = document.execCommand('copy');
                document.body.removeChild(textarea);
                if (success) {
                    ElementPlus.ElMessage.success('已复制到剪贴板');
                } else {
                    ElementPlus.ElMessage.error('复制失败，请手动复制');
                }
            } catch (e) {
                console.error('降级复制方案也失败', e);
                ElementPlus.ElMessage.error('复制失败，请手动复制');
            }
        },

        /* ---------- 折叠/展开 ---------- */

        /**
         * 判断指定消息的指定区域是否处于折叠状态
         * @param {string} msgId - 消息 ID
         * @param {string} section - 区域标识：'thinking' | 'tool' | 'toolcalls'
         * @returns {boolean} 是否折叠
         */
        isCollapsed(msgId, section) {
            const key = msgId + '_' + section;
            if (ctx.collapsedState[key] !== undefined) {
                return ctx.collapsedState[key];
            }
            // 默认：思考过程展开，工具信息和工具调用折叠
            return section === 'tool' || section === 'toolcalls';
        },

        /**
         * 切换指定消息指定区域的折叠/展开状态
         * @param {string} msgId - 消息 ID
         * @param {string} section - 区域标识
         */
        toggleCollapse(msgId, section) {
            const key = msgId + '_' + section;
            const currentlyCollapsed = api.isCollapsed(msgId, section);

            const el = document.querySelector(`[data-collapse-key="${key}"]`);
            if (!el) {
                ctx.collapsedState[key] = !currentlyCollapsed;
                return;
            }

            if (currentlyCollapsed) {
                // === 展开 ===
                el.style.transition = 'none';
                const targetHeight = el.scrollHeight;
                el.style.maxHeight = '0px';

                ctx.collapsedState[key] = false;

                Vue.nextTick(() => {
                    el.offsetHeight; // 强制重排
                    el.style.transition = '';
                    el.style.maxHeight = targetHeight + 'px';

                    const onEnd = () => {
                        el.style.maxHeight = '';
                        el.style.transition = '';
                        el.removeEventListener('transitionend', onEnd);
                    };
                    el.addEventListener('transitionend', onEnd);
                });
            } else {
                // === 收起 ===
                el.style.transition = 'none';
                el.style.maxHeight = el.scrollHeight + 'px';

                ctx.collapsedState[key] = true;

                Vue.nextTick(() => {
                    el.offsetHeight; // 强制重排
                    el.style.transition = '';
                    el.style.maxHeight = '0px';
                });
            }
        },

        /* ---------- 插件渲染槽 ---------- */

        /**
         * 注册一个消息区扩展组件。插件在启动阶段调用一次即可，
         * 之后对应锚点处会渲染它，并把 { msg } 作为 prop 传入。
         *
         * 组件契约（约定而非强制）：
         *   props: { msg: Object }     — 当前消息（列表顶部锚点为 null）
         *   inject: messageAreaContext — 与内置子组件共用同一份共享上下文
         *
         * @param {string} anchor - 锚点名：见文件顶部 MessageAreaPlugins 说明
         * @param {object} component - Vue 组件选项对象
         * @param {number} [order=0] - 排序权重，越小越靠前
         * @returns {object} 已登记的槽对象（可用于 unregister）
         */
        registerSlot(anchor, component, order) {
            if (!anchor || !component) return null;
            // 写入全局注册表，保证后续新建的 context 也能带上；本次渲染直接进当前 ctx
            MessageAreaPlugins.registerSlot(anchor, component, order);
            const arr = api.slotsFor(anchor);
            const slot = { component: component, order: order || 0 };
            arr.push(slot);
            arr.sort((a, b) => a.order - b.order);
            return slot;
        },

        /**
         * 注销已注册的扩展组件。
         * @param {string} anchor - 锚点名
         * @param {object} component - 注册时传入的组件对象，或 registerSlot 返回的槽对象
         */
        unregisterSlot(anchor, component) {
            const arr = api.slotsFor(anchor);
            const idx = arr.findIndex(s => s === component || s.component === component);
            if (idx !== -1) arr.splice(idx, 1);
        },

        /** 取某锚点当前的槽数组（响应式，直接用于 v-for） */
        slotsFor(anchor) {
            if (!ctx.slots[anchor]) ctx.slots[anchor] = [];
            return ctx.slots[anchor];
        },

        /* ---------- 兼容旧 API（等价于 assistant-actions 锚点） ---------- */

        registerAssistantActionSlot(component, order) {
            return api.registerSlot('assistant-actions', component, order);
        },

        unregisterAssistantActionSlot(component) {
            api.unregisterSlot('assistant-actions', component);
        }
    };

    ctx.actions = api;
    return ctx;
}
