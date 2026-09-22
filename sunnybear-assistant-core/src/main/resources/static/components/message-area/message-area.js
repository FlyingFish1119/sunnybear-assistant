/**
 * 消息区组件（骨架）
 *
 * 组件只负责装配：新对话落地页（message-area-hero 子组件）、加载态、
 * 消息分组循环（_message-area-group 子组件）与上下文压缩卡片
 * （message-area-compress 子组件）。
 *
 * 具体 UI 已各归其主：
 *   message-area-context.js        — 共享状态（编辑态/折叠态/头像兜底）+ 动作集
 *   message-area-group/message-area-group.js           — 组导轨 + 组头
 *   message-area-user/message-area-user.js             — 用户消息气泡
 *   message-area-assistant/message-area-assistant.js   — 助手消息气泡
 *   message-area-tool/message-area-tool.js             — 工具消息气泡
 *
 * Props:
 *   mainColor         — String  主题色
 *   userSettings      — Object  用户设置（头像 / 用户名）
 *   assistantSettings — Object  助手设置（头像 / 名称）
 *
 * Injects:
 *   sessionStore      — 会话/消息仓库；currentMessages / currentSessionId /
 *                       isStreaming / sessionSelectLoading 均取自仓库
 *
 * 依赖全局：$md（MarkdownUtils）、ElementPlus、lucide、auto-follow 指令。
 */

const MessageArea = {
    name: 'MessageArea',

    template: `
    <div class="message-area-panel" :class="{ 'is-new-chat': isNewChat }">
        <!-- 新对话落地页：头像 + 问候 + 建议提问（message-area-hero 子组件） -->
        <message-area-hero
            v-if="isNewChat"
            :main-color="mainColor"
            :avatar="assistantAvatar"
            :assistant-name="assistantSettings.assistantName"
        ></message-area-hero>
        <!-- 消息列表：新对话时隐藏 -->
        <div v-show="currentSessionId || currentMessages.length > 0" class="message-area-list" v-auto-follow>
            <div :style="{'--main-color': mainColor}" class="message-area-list-loading" v-if="sessionSelectLoading">
                <div class="loading-spinner">
                    <i data-lucide="loader-circle" class="loading-icon"></i>
                </div>
                <span class="loading-text">加载中</span>
                <span class="loading-dots"><span>.</span><span>.</span><span>.</span></span>
            </div>
            <message-area-group
                v-for="group in messageGroups"
                :key="'group-' + group.messages[0].id"
                :group="group">
            </message-area-group>
            <!-- 上下文压缩卡片（message-area-compress 子组件）：长对话总结旧历史期间
                 顶替空占位，避免在「等待回复」处呆等 -->
            <message-area-compress :main-color="mainColor"></message-area-compress>
        </div>
    </div>`,

    props: {
        mainColor:         { type: String, default: 'lightsalmon' },
        userSettings:      { type: Object, default: function () { return { background: '', opacity: 0.3 }; } },
        assistantSettings: { type: Object, default: function () { return { avatar: '', assistantName: '' }; } }
    },

    inject: {
        // 主应用必注：会话/消息仓库
        sessionStore: { required: true }
    },

    /**
     * provide：sessionStore 与共享上下文一并下发，供 group/user/assistant/tool
     * 子组件注入使用。
     */
    provide: function () {
        return {
            sessionStore: this.sessionStore,
            messageAreaContext: this.context
        };
    },

    data: function () {
        // 在 data 里创建共享上下文（provide 在 data 之后求值，这里创建才能被 provide 捕获）
        const context = createMessageAreaContext(this.sessionStore);
        context.mainColor = this.mainColor;
        context.userSettings = this.userSettings;
        context.assistantSettings = this.assistantSettings;
        return {
            context: context
        };
    },

    watch: {
        mainColor: function (v) { if (this.context) this.context.mainColor = v; },
        userSettings: function (v) { if (this.context) this.context.userSettings = v; },
        assistantSettings: function (v) { if (this.context) this.context.assistantSettings = v; }
    },

    computed: {
        currentMessages: function () {
            return this.sessionStore.state.currentMessages;
        },
        // 按用户消息切分渲染组：assistant/tool 连续段归为一个 ReAct 组（共用一个头像+竖线）
        // 流式期间只有当前消息的内容变化，只有它所在的子组件重渲染，
        // 历史分组作为独立组件实例保持不动
        messageGroups: function () {
            const groups = [];
            let current = null;
            for (const msg of this.currentMessages) {
                if (msg.role === 'user') {
                    groups.push({ role: 'user', messages: [msg] });
                    current = null;
                } else {
                    if (!current) {
                        current = { role: 'assistant', messages: [] };
                        groups.push(current);
                    }
                    current.messages.push(msg);
                }
            }
            return groups;
        },
        currentSessionId: function () {
            return this.sessionStore.currentSessionId;
        },
        sessionSelectLoading: function () {
            return this.sessionStore.sessionSelectLoading;
        },
        isNewChat: function () {
            return !this.currentSessionId && this.currentMessages.length === 0;
        },
        /**
         * 助手头像 URL（本地文件走代理），供落地页子组件使用。
         * 消息头像走 messageAreaContext.actions.getMessageAvatar（含加载失败兜底）；
         * 落地页的兜底在子组件里，这里只做 URL 解析。
         */
        assistantAvatar: function () {
            return this.$fileUrl.proxy(this.assistantSettings.avatar);
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons();
        // 代码块复制按钮（v-html 生成，无法用 Vue @click，走事件委托）
        this._onDocClick = (e) => {
            const btn = e.target.closest('.code-copy-btn');
            if (!btn) return;
            const wrapper = btn.closest('.code-block-wrapper');
            const code = wrapper && wrapper.querySelector('pre code');
            if (code) {
                this.context.actions.writeToClipboard(code.textContent);
            }
        };
        document.addEventListener('click', this._onDocClick);
    },

    beforeUnmount: function () {
        if (this._onDocClick) {
            document.removeEventListener('click', this._onDocClick);
        }
    }
};
