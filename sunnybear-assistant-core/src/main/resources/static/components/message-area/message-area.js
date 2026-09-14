/**
 * 消息区组件
 *
 * 把原先内联在 index.html 的消息列表整体拆出，连同其绑定的全部状态与操作
 * （编辑 currentEditId/editDraft/editTargetRole、折叠 collapsedState、头像错误兜底）
 * 内聚到本组件。会话与消息数据统一来自注入的 sessionStore；replace / edit / 播放语音
 * 等动作委托 store，组件自身不直接持有 WebSocket。
 *
 * Props:
 *   mainColor         — String  主题色
 *   userSettings      — Object  用户设置（头像 / 用户名）
 *   assistantSettings — Object  助手设置（头像 / 名称）
 *
 * 空会话问候语由组件自行向 API.greeting.random() 请求，无需父级传入。
 *
 * Injects:
 *   sessionStore      — 会话/消息仓库；currentMessages / currentSessionId /
 *                       isStreaming / sessionSelectLoading 均取自仓库
 *
 * 依赖全局：$md (MarkdownUtils)、$fileUrl (FileUrlUtils)、ColorUtils、ElementPlus、
 *           MermaidUtils、auto-follow 指令、auto-resize-textarea 组件。
 */

/** 取数组最后一项（判断"思考中"用） */
function getLast(arr) {
    if (arr === null || arr.length === 0) return null;
    return arr[arr.length - 1];
}

/** 建议提问的图标：后端只回文本，前端按顺序轮换配图标 */
const SUGGESTION_ICONS = ['pen-line', 'file-text', 'lightbulb', 'compass'];

const MessageArea = {
    name: 'MessageArea',

    template: `
    <div class="message-area-panel" :class="{ 'is-new-chat': isNewChat }">
        <!-- 新对话落地页：头像 + 问候 + 建议提问 -->
        <div v-if="isNewChat" class="new-chat-hero" :style="{'--main-color': mainColor}">
            <div class="hero-avatar">
                <img v-if="heroAvatar" :src="heroAvatar" alt="" @error="assistantAvatarError = true">
                <i v-else :data-lucide="greetingIcon" class="hero-avatar-icon"></i>
            </div>
            <p class="hero-greeting">{{ greetingText || '你好，我能帮你做点什么？' }}</p>
            <p class="hero-subtitle">{{ heroSubtitle }}</p>
            <div class="hero-suggestions">
                <button v-for="(item, index) in suggestions"
                        :key="index"
                        class="hero-suggestion"
                        @click="useSuggestion(item.text)">
                    <i :data-lucide="item.icon" class="hero-suggestion-icon"></i>
                    <span>{{ item.text }}</span>
                </button>
            </div>
        </div>
        <!-- 消息列表：新对话时隐藏 -->
        <div v-show="currentSessionId || currentMessages.length > 0" class="message-area-list" v-auto-follow>
            <div :style="{'--main-color': mainColor}" class="message-area-list-loading" v-if="sessionSelectLoading">
                <div class="loading-spinner">
                    <i data-lucide="loader-circle" class="loading-icon"></i>
                </div>
                <span class="loading-text">加载中</span>
                <span class="loading-dots"><span>.</span><span>.</span><span>.</span></span>
            </div>
            <template v-for="group in messageGroups" :key="'group-' + group.messages[0].id">
                <div class="message-area-row" :class="group.role">
                    <!-- 助手组左侧导轨：头像（仅组第一条）+ 主色淡竖线 -->
                    <div v-if="group.role !== 'user'" class="react-rail">
                        <img v-if="getMessageAvatar(group.messages[0])"
                             :src="getMessageAvatar(group.messages[0])"
                             class="message-avatar-top react-avatar"
                             :alt="group.messages[0].name + '头像'"
                             @error="assistantAvatarError = true">
                        <div v-else class="message-avatar-top react-avatar message-avatar-default assistant-avatar-default">
                            <span>{{ getAvatarInitial(group.messages[0]) }}</span>
                        </div>
                        <span v-if="group.messages.length > 1" class="react-line"></span>
                    </div>
                    <div class="message-area-col">
                        <!-- 名称 + 时间：仅组的第一条显示（用户消息头像在名称旁） -->
                        <div class="message-header-row">
                            <template v-if="group.role === 'user'">
                                <img v-if="getMessageAvatar(group.messages[0])"
                                     :src="getMessageAvatar(group.messages[0])"
                                     class="message-avatar-top"
                                     :alt="group.messages[0].name + '头像'"
                                     @error="userAvatarError = true">
                                <div v-else class="message-avatar-top message-avatar-default user-avatar-default">
                                    <span>{{ getAvatarInitial(group.messages[0]) }}</span>
                                </div>
                            </template>
                            <span class="message-header-meta">{{ group.messages[0].name }} · {{ group.messages[0].createTime }}</span>
                        </div>
                        <template v-for="msg in group.messages" :key="msg.id">
                        <div v-if="msg.role !== 'tool'" class="message-area-bubble" :style="msg.role === 'user' ? {'background-color': userBubbleBg} : {}">
                        <div v-if="msg.reasoningContent !== null && msg.reasoningContent.length > 0">
                            <div class="message-area-bubble-meta reasoning-header" @click="toggleCollapse(msg.id, 'thinking')">
                                <i style="width: 10px; height: 10px" data-lucide="sparkle"></i>
                                <span>思考过程:</span>
                                <span v-if="isThinkingMsg(msg)" :style="{'--main-color': mainColor}" class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
                                <i :class="['reasoning-chevron', { collapsed: isCollapsed(msg.id, 'thinking') }]" style="width: 14px; height: 14px" data-lucide="chevron-down"></i>
                            </div>
                            <div :class="['collapsible-content', { collapsed: isCollapsed(msg.id, 'thinking') }]" :data-collapse-key="msg.id + '_thinking'">
                                <div class="message-area-reasoning markdown-body" v-html="$md.render(msg.reasoningContent)"></div>
                            </div>
                        </div>
                        <!-- 编辑模式：显示 textarea -->
                        <div v-if="currentEditId === msg.id">
                            <auto-resize-textarea
                                class="message-edit-textarea"
                                :main-color="mainColor"
                                v-model="editDraft"
                                :max-height="300"
                                :min-height="65"
                                @submit="editTargetRole === 'assistant' ? confirmAssistantEdit(msg) : confirmEdit(msg)"
                                @cancel="editTargetRole === 'assistant' ? cancelAssistantEdit() : cancelEdit()"
                            ></auto-resize-textarea>
                        </div>
                        <!-- 正常模式：显示内容 -->
                        <div v-else v-for="(content, idx) in msg.contents" :key="idx">
                            <div v-if="content.type === 'text'" class="markdown-body" v-html="$md.render(content.content)"></div>
                            <div v-else-if="content.type === 'image'" class="message-attachment-image">
                                <img :src="$fileUrl.proxy(content.url)" @click.stop="$fileUrl.previewImage(content.url)" />
                            </div>
                            <div v-else-if="content.type === 'audio'" class="message-attachment-audio">
                                <audio :src="$fileUrl.proxy(content.url)" controls preload="metadata"></audio>
                            </div>
                            <div v-else-if="content.type === 'video'" class="message-attachment-video">
                                <video :src="$fileUrl.proxy(content.url)" controls preload="metadata"></video>
                            </div>
                            <div v-else-if="content.type === 'file'" class="message-attachment-file">
                                <a :href="$fileUrl.proxy(content.url)" target="_blank">
                                    <i data-lucide="file" style="width:18px;height:18px"></i>
                                    <span>{{ $fileUrl.fileName(content.url) }}</span>
                                </a>
                            </div>
                        </div>
                        <div v-if="msg.toolCalls && msg.toolCalls.length > 0" :class="['tool-calls-block', { collapsed: isCollapsed(msg.id, 'toolcalls') }]">
                            <div class="message-area-bubble-tools-header" @click="toggleCollapse(msg.id, 'toolcalls')">
                                <i :class="['tool-chevron', { collapsed: isCollapsed(msg.id, 'toolcalls') }]" style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
                                <span class="markdown-body" v-html="$md.render(msg.toolCalls.map(t => '\`' + t.name + '\`').join(', '))"></span>
                            </div>
                            <div :class="['message-area-bubble-tools-wrap', { collapsed: isCollapsed(msg.id, 'toolcalls') }]" :data-collapse-key="msg.id + '_toolcalls'">
                                <div class="message-area-bubble-tools markdown-body" v-html="$md.render(msg.toolCalls.map(t => '\`\`\`' + t.name + '\\n' + (t.arguments ? $md.beautify(t.arguments) : '') + '\\n\`\`\`').join('\\n\\n'))">
                                </div>
                            </div>
                        </div>
                        <!-- streaming 时空占位，防止高度抽搐 -->
                        <div v-if="(msg.siblingCount > 1 || msg.role === 'assistant' || msg.role === 'user') && isStreaming"
                             class="message-area-bubble-actions" style="visibility: hidden;"></div>
                        <!-- 编辑模式：确认/取消按钮，始终可见 -->
                        <div v-else-if="currentEditId === msg.id" class="message-area-bubble-actions" style="opacity: 1;">
                            <span class="branch-switch-arrow"
                                  @click.stop="editTargetRole === 'assistant' ? confirmAssistantEdit(msg) : confirmEdit(msg)"
                                  title="确认编辑">
                                <i style="width: 14px; height: 14px" data-lucide="check"></i>
                            </span>
                            <span class="branch-switch-arrow"
                                  @click.stop="editTargetRole === 'assistant' ? cancelAssistantEdit() : cancelEdit()"
                                  title="取消编辑">
                                <i style="width: 14px; height: 14px" data-lucide="x"></i>
                            </span>
                        </div>
                        <!-- 真实按钮区，当前消息不在编辑模式时显示 -->
                        <div v-else-if="(msg.siblingCount > 1 || msg.role === 'assistant' || msg.role === 'user') && currentEditId !== msg.id" class="message-area-bubble-actions">
                            <span v-if="msg.siblingCount > 1" class="branch-switch-arrow"
                                  :class="{ disabled: msg.siblingIndex === 0 }"
                                  @click.stop="switchBranch(msg, 'left')"
                                  title="切换到上一个分支">
                                <i style="width: 14px; height: 14px" data-lucide="chevron-left"></i>
                            </span>
                            <span v-if="msg.siblingCount > 1" class="branch-switch-counter">{{ msg.siblingIndex + 1 }} / {{ msg.siblingCount }}</span>
                            <span v-if="msg.siblingCount > 1" class="branch-switch-arrow"
                                  :class="{ disabled: msg.siblingIndex === msg.siblingCount - 1 }"
                                  @click.stop="switchBranch(msg, 'right')"
                                  title="切换到下一个分支">
                                <i style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
                            </span>
                            <span v-if="msg.role === 'assistant'" class="branch-switch-arrow"
                                  @click.stop="replaceBranch(msg)"
                                  title="重新生成回复">
                                <i style="width: 14px; height: 14px" data-lucide="rotate-ccw"></i>
                            </span>
                            <span v-if="msg.role === 'assistant'" class="branch-switch-arrow"
                                  @click.stop="copyMessage(msg)"
                                  title="复制消息">
                                <i style="width: 14px; height: 14px" data-lucide="copy"></i>
                            </span>
                            <span v-if="msg.role === 'assistant' && msg.extension && msg.extension.ttsAudio"
                                  class="branch-switch-arrow"
                                  @click.stop="playMessageAudio(msg)"
                                  title="播放语音回复">
                                <i style="width: 14px; height: 14px" data-lucide="volume-2"></i>
                            </span>
                            <span v-if="msg.role === 'assistant'" class="branch-switch-arrow"
                                  @click.stop="startAssistantEdit(msg)"
                                  title="编辑消息">
                                <i style="width: 14px; height: 14px" data-lucide="pencil"></i>
                            </span>
                            <span v-if="msg.role === 'user'" class="branch-switch-arrow"
                                  @click.stop="startEdit(msg)"
                                  title="编辑消息">
                                <i style="width: 14px; height: 14px" data-lucide="pencil"></i>
                            </span>
                            <span v-if="msg.role === 'user'" class="branch-switch-arrow"
                                  @click.stop="copyMessage(msg)"
                                  title="复制消息">
                                <i style="width: 14px; height: 14px" data-lucide="copy"></i>
                            </span>
                            <span v-if="msg.role === 'user'" class="branch-switch-arrow"
                                  @click.stop="deleteUserMessage(msg)"
                                  title="删除消息">
                                <i style="width: 14px; height: 14px" data-lucide="trash-2"></i>
                            </span>
                        </div>
                        </div>
                        <div v-else class="message-area-bubble tool-bubble">
                    <div class="message-area-bubble-meta tool-meta-header" @click="toggleCollapse(msg.id, 'tool')">
                        <i :class="['tool-chevron', { collapsed: isCollapsed(msg.id, 'tool') }]" style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
                        <span>{{ msg.name }} · {{ msg.createTime }}</span>
                        <span class="tool-status-inline" :style="{color: $toolStatus(msg).color}">
                            <i style="width: 12px; height: 12px" :data-lucide="$toolStatus(msg).icon"></i>
                            <span style="padding-left: 2px">{{ $toolStatus(msg).text }}</span>
                        </span>
                    </div>
                    <div :class="['collapsible-content', { collapsed: isCollapsed(msg.id, 'tool') }]" :data-collapse-key="msg.id + '_tool'">
                        <div v-for="(content, idx) in msg.contents" :key="idx">
                            <div v-if="content.type === 'text'">
                                <template v-if="msg.extension && msg.extension.status === 'executing'">
                                    <div class="message-area-bubble-tools-meta">
                                        <i style="width: 12px; height: 12px; color: #faad14" data-lucide="circle-dot"></i>
                                        <div style="padding: 0 0 1px 4px; color: #faad14">[{{msg.name}}]执行中…</div>
                                    </div>
                                </template>
                                <template v-else>
                                    <span v-if="$toolParsed(content).succeed" class="message-area-bubble-tools-meta">
                                        <i style="width: 12px; height: 12px; color: #52c41a" data-lucide="circle-check"></i>
                                        <span style="padding: 0 0 1px 4px; color: #52c41a">[{{msg.name}}]执行成功</span>
                                    </span>
                                    <div v-else class="message-area-bubble-tools-meta">
                                        <i style="width: 12px; height: 12px; color: #ff4d4f" data-lucide="circle-x"></i>
                                        <div style="padding: 0 0 1px 4px; color: #ff4d4f">[{{msg.name}}]执行失败</div>
                                    </div>
                                    <div class="markdown-body" v-html="$md.render($toolParsed(content).result)"></div>
                                </template>
                            </div>
                            <div v-else-if="content.type === 'image'" class="message-attachment-image">
                                <img :src="$fileUrl.proxy(content.url)" @click.stop="$fileUrl.previewImage(content.url)" />
                            </div>
                            <div v-else-if="content.type === 'audio'" class="message-attachment-audio">
                                <audio :src="$fileUrl.proxy(content.url)" controls preload="metadata"></audio>
                            </div>
                            <div v-else-if="content.type === 'video'" class="message-attachment-video">
                                <video :src="$fileUrl.proxy(content.url)" controls preload="metadata"></video>
                            </div>
                            <div v-else-if="content.type === 'file'" class="message-attachment-file">
                                <a :href="$fileUrl.proxy(content.url)" target="_blank">
                                    <i data-lucide="file" style="width:18px;height:18px"></i>
                                    <span>{{ $fileUrl.fileName(content.url) }}</span>
                                </a>
                            </div>
                        </div>
                    </div>
                </div>
                        </template>
                    </div>
                </div>
            </template>
        </div>
    </div>`,

    props: {
        mainColor:         { type: String, default: 'lightsalmon' },
        userSettings:      { type: Object, default: function () { return { background: '', opacity: 0.3 }; } },
        assistantSettings: { type: Object, default: function () { return { avatar: '', assistantName: '' }; } }
    },

    inject: {
        // 主应用必注：会话/消息仓库
        sessionStore: { required: true },
        // 可选：本地事件总线（用于把建议提问填进发送框）
        wsBus: { default: null }
    },

    data: function () {
        return {
            // 空会话问候语（组件自行请求）
            greetingText: '',
            // 新对话页的「建议提问」：点击填入发送框
            suggestions: [
                { icon: 'pen-line', text: '帮我写一封得体的邮件' },
                { icon: 'file-text', text: '总结这份文档的核心要点' },
                { icon: 'lightbulb', text: '用简单的话解释一个概念' },
                { icon: 'calendar-check', text: '帮我制定一周的作息计划' }
            ],
            // 头像加载失败兜底：失败后回退到默认首字母头像
            userAvatarError: false,
            assistantAvatarError: false,
            // 消息编辑状态
            currentEditId: null,
            editDraft: '',
            editTargetRole: null,
            // 折叠状态：key = msgId_section, value = true(折叠)/false(展开)
            collapsedState: {}
        };
    },

    computed: {
        currentMessages: function () {
            return this.sessionStore.state.currentMessages;
        },
        // 按用户消息切分渲染组：assistant/tool 连续段归为一个 ReAct 组（共用一个头像+竖线）
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
        isStreaming: function () {
            return this.sessionStore.isStreaming;
        },
        sessionSelectLoading: function () {
            return this.sessionStore.sessionSelectLoading;
        },
        isNewChat: function () {
            return !this.currentSessionId && this.currentMessages.length === 0;
        },
        // 用户消息气泡背景色：跟随主色自动变浅
        userBubbleBg: function () {
            return ColorUtils.lighten(this.mainColor, 0.25);
        },
        greetingIcon: function () {
            const hour = new Date().getHours();
            if (hour >= 6 && hour < 12) return 'sunrise';
            if (hour >= 12 && hour < 18) return 'sun';
            if (hour >= 18 && hour < 22) return 'sunset';
            return 'moon';
        },
        // 新对话页副标题：带上助理名，无则用通用引导
        heroSubtitle: function () {
            const name = this.assistantSettings.assistantName;
            return name ? ('和 ' + name + ' 聊点什么，或试试下面的问题')
                        : '试试下面的问题，或直接输入你的想法';
        },
        // 新对话页头像：复用消息头像的 URL 处理（本地文件走代理）
        heroAvatar: function () {
            return this.getMessageAvatar({ role: 'assistant' });
        }
    },

    watch: {
        // 每次进入新对话刷新问候语 + 建议提问（换个花样）
        isNewChat: function (val) {
            if (val) this.fetchGreeting();
        }
    },

    methods: {
        /** 空会话问候语 + 建议提问：组件挂载时自行请求，失败时回退默认文案 */
        async fetchGreeting() {
            try {
                const result = await API.greeting.random();
                if (result.status === 200 && result.data) {
                    this.greetingText = result.data.text;
                    const list = result.data.suggestions;
                    if (Array.isArray(list) && list.length > 0) {
                        // 去重：AI 可能生成重复建议，重复的 v-for key 会导致 DOM 插入异常
                        const unique = [];
                        const seen = {};
                        list.forEach(function (text) {
                            const key = String(text);
                            if (key && !seen[key]) {
                                seen[key] = true;
                                unique.push(text);
                            }
                        });
                        this.suggestions = unique.map(function (text, i) {
                            return { icon: SUGGESTION_ICONS[i % SUGGESTION_ICONS.length], text: text };
                        });
                    }
                } else {
                    this.greetingText = '你好！今天有什么可以帮你的吗？';
                }
            } catch (error) {
                console.error('获取问候语失败:', error);
            }
        },

        /** 点击建议提问：通过本地事件把文案填进发送框（由 send-area 接收并聚焦） */
        useSuggestion(text) {
            if (this.wsBus) {
                this.wsBus.emit('send-area:fill', text);
            }
        },

        /**
         * 带缓存的工具结果 JSON 解析（模板中使用）。
         * 避免每次 Vue 更新都对同一 content 重复 JSON.parse。
         * @param {object} content - 消息的 content 对象
         * @returns {object} 解析后的工具结果
         */
        $toolParsed(content) {
            let raw = content.content;
            if (content._rawJson !== raw) {
                content._rawJson = raw;
                content._parsed = JSON.parse(raw);
            }
            return content._parsed;
        },

        /**
         * 获取工具消息的聚合执行状态（模板中使用）。
         * 在折叠的头部显示成功/失败图标和颜色。
         * @param {object} msg - 工具消息对象
         * @returns {{ succeed: boolean, icon: string, color: string, text: string }}
         */
        $toolStatus(msg) {
            if (msg.extension && msg.extension.status === 'executing') {
                return { succeed: true, icon: 'circle-dot', color: '#faad14', text: '执行中…' };
            }
            if (!msg.contents || msg.contents.length === 0) {
                return { succeed: true, icon: 'circle-check', color: '#52c41a', text: '执行成功' };
            }
            let allSucceed = true;
            for (let content of msg.contents) {
                if (content.type === 'text') {
                    try {
                        let parsed = this.$toolParsed(content);
                        if (!parsed.succeed) {
                            allSucceed = false;
                            break;
                        }
                    } catch (e) {
                        allSucceed = false;
                        break;
                    }
                }
            }
            return allSucceed
                ? { succeed: true, icon: 'circle-check', color: '#52c41a', text: '执行成功' }
                : { succeed: false, icon: 'circle-x', color: '#ff4d4f', text: '执行失败' };
        },

        /** 获取消息对应的头像 URL */
        getMessageAvatar(msg) {
            if (msg.role === 'user' && this.userAvatarError) return '';
            if (msg.role === 'assistant' && this.assistantAvatarError) return '';
            const avatar = msg.role === 'user'
                ? this.userSettings.avatar
                : this.assistantSettings.avatar;
            if (!avatar) return '';
            if (avatar.startsWith('data:')) return avatar;
            if (!/^https?:\/\//i.test(avatar)) {
                return API.fileProxyUrl(avatar);
            }
            return avatar;
        },

        /** 获取消息头像的默认首字母 */
        getAvatarInitial(msg) {
            if (msg.role === 'user') {
                return (this.userSettings.username || 'U').charAt(0);
            }
            return (this.assistantSettings.assistantName || 'A').charAt(0);
        },

        /**
         * 左右切换兄弟分支
         * @param {object} msg - 当前消息对象
         * @param {string} direction - 'left' 或 'right'
         */
        async switchBranch(msg, direction) {
            try {
                const result = await API.message.switchBranch(msg.id, direction);
                if (result.status === 200) {
                    if (this.currentSessionId) {
                        await this.sessionStore.selectSession(this.sessionStore.state.currentSession);
                    }
                } else {
                    ElementPlus.ElMessage.error(result.message || '切换分支失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('切换分支失败:', error);
            }
        },

        /**
         * 重新生成助手回复（replace 模式）：找到父用户消息后委托 store 重发
         * @param {object} msg - 助手消息对象
         */
        replaceBranch(msg) {
            const parentMsg = this.currentMessages.find(m => m.id === msg.parentId);
            if (!parentMsg) {
                ElementPlus.ElMessage.error('未找到对应的用户消息');
                return;
            }
            let userText = '';
            if (parentMsg.contents && parentMsg.contents.length > 0) {
                userText = parentMsg.contents
                    .filter(c => c.type === 'text')
                    .map(c => c.content)
                    .join('\n');
            }
            if (!userText) {
                ElementPlus.ElMessage.error('用户消息内容为空');
                return;
            }
            this.sessionStore.replaceBranch(msg.id, userText);
        },

        /**
         * 进入编辑模式：设置 currentEditId 并初始化草稿
         * @param {object} msg - 用户消息对象
         */
        startEdit(msg) {
            let userText = '';
            if (msg.contents && msg.contents.length > 0) {
                userText = msg.contents
                    .filter(c => c.type === 'text')
                    .map(c => c.content)
                    .join('\n');
            }
            this.currentEditId = msg.id;
            this.editTargetRole = 'user';
            this.editDraft = userText;
            this.focusEditTextarea();
        },

        /**
         * 确认编辑：发送 edit 请求并清除编辑状态
         * @param {object} msg - 用户消息对象
         */
        confirmEdit(msg) {
            if (!this.editDraft.trim()) {
                ElementPlus.ElMessage.error('消息内容不能为空');
                return;
            }
            this.sessionStore.editMessage(msg.id, this.editDraft);
            this.clearEditState();
        },

        /** 取消编辑 */
        cancelEdit() {
            this.clearEditState();
        },

        /**
         * 删除用户消息及其所有子孙消息
         * @param {object} msg - 用户消息对象
         */
        async deleteUserMessage(msg) {
            try {
                const result = await API.message.deleteUser(msg.id);
                if (result.status === 200) {
                    ElementPlus.ElMessage.success('消息已删除');
                    await this.sessionStore.selectSession(this.sessionStore.state.currentSession);
                } else {
                    ElementPlus.ElMessage.error(result.message || '删除消息失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('删除消息失败:', error);
            }
        },

        /**
         * 进入助手消息编辑模式
         * @param {object} msg - 助手消息对象
         */
        startAssistantEdit(msg) {
            let assistantText = '';
            if (msg.contents && msg.contents.length > 0) {
                assistantText = msg.contents
                    .filter(c => c.type === 'text')
                    .map(c => c.content)
                    .join('\n');
            }
            this.currentEditId = msg.id;
            this.editTargetRole = 'assistant';
            this.editDraft = assistantText;
            this.focusEditTextarea();
        },

        /**
         * 确认编辑助手消息
         * @param {object} msg - 助手消息对象
         */
        async confirmAssistantEdit(msg) {
            if (!this.editDraft.trim()) {
                ElementPlus.ElMessage.error('消息内容不能为空');
                return;
            }
            try {
                const result = await API.message.editAssistant({ id: msg.id, content: this.editDraft });
                if (result.status === 200) {
                    const targetMsg = this.currentMessages.find(m => m.id === msg.id);
                    if (targetMsg && targetMsg.contents && targetMsg.contents.length > 0) {
                        const textContent = targetMsg.contents.find(c => c.type === 'text');
                        if (textContent) {
                            textContent.content = this.editDraft;
                        } else {
                            targetMsg.contents.push({ type: 'text', content: this.editDraft });
                        }
                    }
                    this.$nextTick(() => {
                        MermaidUtils.renderAll();
                    });
                    ElementPlus.ElMessage.success('消息已更新');
                } else {
                    ElementPlus.ElMessage.error(result.message || '更新消息失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('更新消息失败:', error);
            } finally {
                this.clearEditState();
            }
        },

        /** 取消助手消息编辑 */
        cancelAssistantEdit() {
            this.clearEditState();
        },

        /** 清空编辑态 */
        clearEditState() {
            this.currentEditId = null;
            this.editDraft = '';
            this.editTargetRole = null;
        },

        /** 聚焦编辑框（双重 rAF 确保 DOM 就绪且 lucide 刷新不影响 focus） */
        focusEditTextarea() {
            this.$nextTick(() => {
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        const el = document.querySelector('.message-edit-textarea');
                        if (el) el.focus();
                    });
                });
            });
        },

        /**
         * 复制消息的原始文本到剪贴板
         * @param {object} msg - 消息对象
         */
        copyMessage(msg) {
            let text = '';
            if (msg.contents && msg.contents.length > 0) {
                text = msg.contents
                    .filter(c => c.type === 'text')
                    .map(c => c.content)
                    .join('\n');
            }
            if (msg.role === 'assistant' && msg.reasoningContent) {
                text = '思考过程:\n' + msg.reasoningContent + (text ? '\n\n' + text : '');
            }
            this.writeToClipboard(text);
        },

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

        /** 消息气泡 🔊：重播整轮完整音频（委托 store → 发送区） */
        playMessageAudio(msg) {
            this.sessionStore.playMessageAudio(msg);
        },

        /* ---------- 折叠/展开 ---------- */

        /**
         * 判断指定消息是否处于"思考中"（流式且只有思维链）
         * @param {object} msg - 消息对象
         * @returns {boolean}
         */
        isThinkingMsg(msg) {
            if (!this.isStreaming || !(msg.role === 'assistant' && msg.id && msg.id.startsWith('streaming_'))) return false;
            const hasReasoning = msg.reasoningContent != null && msg.reasoningContent.length > 0;
            const lastContent = getLast(msg.contents);
            const bodyStarted = lastContent && lastContent.content && lastContent.content.length > 0;
            return hasReasoning && !bodyStarted;
        },

        /**
         * 判断指定消息的指定区域是否处于折叠状态
         * @param {string} msgId - 消息 ID
         * @param {string} section - 区域标识：'thinking' | 'tool' | 'toolcalls'
         * @returns {boolean} 是否折叠
         */
        isCollapsed(msgId, section) {
            const key = msgId + '_' + section;
            if (this.collapsedState[key] !== undefined) {
                return this.collapsedState[key];
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
            const currentlyCollapsed = this.isCollapsed(msgId, section);

            const el = document.querySelector(`[data-collapse-key="${key}"]`);
            if (!el) {
                this.collapsedState[key] = !currentlyCollapsed;
                return;
            }

            if (currentlyCollapsed) {
                // === 展开 ===
                el.style.transition = 'none';
                const targetHeight = el.scrollHeight;
                el.style.maxHeight = '0px';

                this.collapsedState[key] = false;

                this.$nextTick(() => {
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

                this.collapsedState[key] = true;

                this.$nextTick(() => {
                    el.offsetHeight; // 强制重排
                    el.style.transition = '';
                    el.style.maxHeight = '0px';
                });
            }
        }
    },

    mounted: function () {
        if (typeof lucide !== 'undefined') lucide.createIcons();
        this.fetchGreeting();
        // 代码块复制按钮（v-html 生成，无法用 Vue @click，走事件委托）
        this._onDocClick = (e) => {
            const btn = e.target.closest('.code-copy-btn');
            if (!btn) return;
            const wrapper = btn.closest('.code-block-wrapper');
            const code = wrapper && wrapper.querySelector('pre code');
            if (code) {
                this.writeToClipboard(code.textContent);
            }
        };
        document.addEventListener('click', this._onDocClick);
    },

    beforeUnmount: function () {
        if (this._onDocClick) {
            document.removeEventListener('click', this._onDocClick);
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
