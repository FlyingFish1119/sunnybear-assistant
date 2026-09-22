/**
 * 助手消息气泡组件。
 *
 * 负责助手消息气泡的全部内容：本轮请求状态（连接中/思考中）、思考过程、
 * 正文/附件（含流式按块渲染）、工具调用参数折叠块、操作按钮（分支/重新生成/
 * 复制/语音/编辑）。
 * 消息自身的动作（重新生成 / 复制 / 语音 / 编辑）由本组件自持，直接调用
 * store / API；跨组件的共享状态（折叠态、编辑态、主色、头像）仍走共享上下文。
 *
 * Injects:
 *   sessionStore       — 会话/消息仓库（isStreaming / busy / requestState / replaceBranch）
 *   messageAreaContext — 消息区共享上下文（折叠态 / 编辑态 / 通用工具动作）
 *
 * 依赖全局：API、ElementPlus、$md、$fileUrl、auto-resize-textarea、
 *           message-area-tokens、lucide。
 */

const MessageAreaAssistant = {
    name: 'MessageAreaAssistant',

    inject: {
        sessionStore: { required: true },
        messageAreaContext: { required: true }
    },

    props: {
        msg: { type: Object, required: true }
    },

    template: `
    <div class="message-area-bubble"
         :data-msg-id="msg.id">
        <!-- 本轮请求状态：连接中 / 思考中（后端 REQUEST_CONNECTING / REQUEST_THINKING 驱动，
             首个产出帧到达即清除）。只在在途气泡上出现，历史消息不会带出残留状态 -->
        <div v-if="isStreamingMsg && requestState" class="request-state">
            <i class="request-state-icon"
               :class="{ 'request-state-spin': requestState === 'connecting' }"
               :data-lucide="requestState === 'connecting' ? 'loader-circle' : 'sparkle'"></i>
            <span class="request-state-text">{{ requestState === 'connecting' ? '连接中' : '思考中' }}</span>
            <span class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
        </div>
        <div v-if="msg.reasoningContent !== null && msg.reasoningContent.length > 0">
            <div class="message-area-bubble-meta reasoning-header" @click="ctx.actions.toggleCollapse(msg.id, 'thinking')">
                <i style="width: 10px; height: 10px" data-lucide="sparkle"></i>
                <span>思考过程:</span>
                <span v-if="isThinkingMsg" :style="{'--main-color': ctx.mainColor}" class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
                <i :class="['reasoning-chevron', { collapsed: ctx.actions.isCollapsed(msg.id, 'thinking') }]" style="width: 14px; height: 14px" data-lucide="chevron-down"></i>
            </div>
            <div :class="['collapsible-content', { collapsed: ctx.actions.isCollapsed(msg.id, 'thinking') }]" :data-collapse-key="msg.id + '_thinking'">
                <!-- 流式期间按块渲染：已闭合的块 HTML 不再变化，DOM 不重建 -->
                <div v-if="isStreamingMsg" class="message-area-reasoning markdown-body md-streaming">
                    <div v-for="(block, bi) in $md.streamBlocks(msg.reasoningContent)" :key="bi" class="md-block" v-html="block.html"></div>
                </div>
                <div v-else class="message-area-reasoning markdown-body" v-html="renderReasoning()"></div>
            </div>
        </div>
        <!-- 编辑模式：显示 textarea -->
        <div v-if="ctx.currentEditId === msg.id">
            <auto-resize-textarea
                class="message-edit-textarea"
                :main-color="ctx.mainColor"
                v-model="ctx.editDraft"
                :max-height="300"
                :min-height="65"
                @submit="confirmAssistantEdit()"
                @cancel="cancelAssistantEdit()"
            ></auto-resize-textarea>
        </div>
        <!-- 正常模式：显示内容 -->
        <div v-else>
            <div v-for="(content, idx) in msg.contents" :key="idx" class="message-area-content-block">
                <!-- 文本块：统一走 message-area-text（默认 Markdown / 插件可自定义渲染） -->
                <message-area-text v-if="content.type === 'text'"
                                   :content="content"
                                   :msg="msg"
                                   :streaming="isStreamingMsg"></message-area-text>
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
        <div v-if="msg.toolCalls && msg.toolCalls.length > 0" :class="['tool-calls-block', { collapsed: ctx.actions.isCollapsed(msg.id, 'toolcalls') }]">
            <div class="message-area-bubble-tools-header" @click="ctx.actions.toggleCollapse(msg.id, 'toolcalls')">
                <i :class="['tool-chevron', { collapsed: ctx.actions.isCollapsed(msg.id, 'toolcalls') }]" style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
                <span class="markdown-body" v-html="toolCallsHeaderMarkdown"></span>
            </div>
            <div :class="['message-area-bubble-tools-wrap', { collapsed: ctx.actions.isCollapsed(msg.id, 'toolcalls') }]" :data-collapse-key="msg.id + '_toolcalls'">
                <div v-if="isStreamingMsg" class="message-area-bubble-tools markdown-body md-streaming">
                    <div v-for="(block, bi) in $md.streamBlocks(toolCallsMarkdown)" :key="bi" class="md-block" v-html="block.html"></div>
                </div>
                <div v-else class="message-area-bubble-tools markdown-body" v-html="renderToolCalls()">
                </div>
            </div>
        </div>
        <!-- streaming 时空占位，防止高度抽搐 -->
        <div v-if="busy" class="message-area-bubble-actions" style="visibility: hidden;"></div>
        <!-- 编辑模式：确认/取消按钮，始终可见 -->
        <div v-else-if="ctx.currentEditId === msg.id" class="message-area-bubble-actions" style="opacity: 1;">
            <span class="branch-switch-arrow"
                  @click.stop="confirmAssistantEdit()"
                  title="确认编辑">
                <i style="width: 14px; height: 14px" data-lucide="check"></i>
            </span>
            <span class="branch-switch-arrow"
                  @click.stop="cancelAssistantEdit()"
                  title="取消编辑">
                <i style="width: 14px; height: 14px" data-lucide="x"></i>
            </span>
        </div>
        <!-- 真实按钮区 -->
        <div v-else class="message-area-bubble-actions">
            <span v-if="!ctx.actions.isHidden('assistant-branch') && msg.siblingCount > 1" class="branch-switch-arrow"
                  :class="{ disabled: msg.siblingIndex === 0 }"
                  @click.stop="ctx.actions.switchBranch(msg, 'left')"
                  title="切换到上一个分支">
                <i style="width: 14px; height: 14px" data-lucide="chevron-left"></i>
            </span>
            <span v-if="!ctx.actions.isHidden('assistant-branch') && msg.siblingCount > 1" class="branch-switch-counter">{{ msg.siblingIndex + 1 }} / {{ msg.siblingCount }}</span>
            <span v-if="!ctx.actions.isHidden('assistant-branch') && msg.siblingCount > 1" class="branch-switch-arrow"
                  :class="{ disabled: msg.siblingIndex === msg.siblingCount - 1 }"
                  @click.stop="ctx.actions.switchBranch(msg, 'right')"
                  title="切换到下一个分支">
                <i style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
            </span>
            <span v-if="!ctx.actions.isHidden('assistant-replace')" class="branch-switch-arrow"
                  @click.stop="replaceBranch()"
                  title="重新生成回复">
                <i style="width: 14px; height: 14px" data-lucide="rotate-ccw"></i>
            </span>
            <span v-if="!ctx.actions.isHidden('assistant-copy')" class="branch-switch-arrow"
                  @click.stop="copyMessage()"
                  title="复制消息">
                <i style="width: 14px; height: 14px" data-lucide="copy"></i>
            </span>
            <span v-if="!ctx.actions.isHidden('assistant-play') && msg.extension && msg.extension.ttsAudio"
                  class="branch-switch-arrow"
                  @click.stop="playMessageAudio()"
                  title="播放语音回复">
                <i style="width: 14px; height: 14px" data-lucide="volume-2"></i>
            </span>
            <span v-if="!ctx.actions.isHidden('assistant-edit')" class="branch-switch-arrow"
                  @click.stop="startAssistantEdit()"
                  title="编辑消息">
                <i style="width: 14px; height: 14px" data-lucide="pencil"></i>
            </span>
            <!-- 本轮 token 消耗（message-area-tokens 子组件） -->
            <message-area-tokens :msg="msg"></message-area-tokens>
            <!-- 操作区插件槽（锚点 'assistant-actions'） -->
            <component v-for="(slot, si) in actionSlots"
                       :key="'plugin-action-' + si"
                       :is="slot.component"
                       :msg="msg"></component>
        </div>
        <!-- 气泡末尾插件槽（锚点 'message-bubble'，插件自行按 msg 判断是否渲染） -->
        <div v-if="bubbleSlots.length" class="message-area-plugin-slots">
            <component v-for="(slot, si) in bubbleSlots"
                       :key="'plugin-bubble-' + si"
                       :is="slot.component"
                       :msg="msg"></component>
        </div>
    </div>`,

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        isStreamingMsg: function () {
            return this.ctx.actions.isStreamingMsg(this.msg);
        },
        isThinkingMsg: function () {
            return this.ctx.actions.isThinkingMsg(this.msg);
        },
        requestState: function () {
            return this.sessionStore.requestState;
        },
        busy: function () {
            return this.sessionStore.busy;
        },
        /** 操作区插件槽（锚点 'assistant-actions'） */
        actionSlots: function () {
            return this.ctx.actions.slotsFor('assistant-actions');
        },
        /** 气泡末尾插件槽（锚点 'message-bubble'） */
        bubbleSlots: function () {
            return this.ctx.actions.slotsFor('message-bubble');
        },
        /** 工具调用参数的 markdown 文本（流式分块渲染与非流式整段渲染共用同一份拼装逻辑） */
        toolCallsMarkdown: function () {
            return this.msg.toolCalls.map(t => [
                '```' + t.name,
                t.arguments ? this.$md.beautify(t.arguments) : '',
                '```'
            ].join(MSG_NL)).join(MSG_NL + MSG_NL);
        },
        /** 折叠头：工具名列表（markdown 行内代码） */
        toolCallsHeaderMarkdown: function () {
            return this.msg.toolCalls.map(t => '`' + t.name + '`').join(', ');
        }
    },

    methods: {
        /** 思考过程 Markdown（缓存到 message 对象的 reasoning 槽） */
        renderReasoning() {
            void this.ctx.mermaidNonce;
            return memoMsgHtml(this.msg, 'reasoning', this.msg.reasoningContent, this.$md.render);
        },

        /** 工具调用参数 Markdown（非流式整段渲染；缓存到 message 对象的 calls 槽） */
        renderToolCalls() {
            void this.ctx.mermaidNonce;
            return memoMsgHtml(this.msg, 'calls', this.toolCallsMarkdown, this.$md.render);
        },

        /* ---------- 消息自身动作（重新生成 / 复制 / 语音 / 编辑） ---------- */

        /**
         * 重新生成助手回复（replace 模式）：找到父用户消息后委托 store 重发
         */
        replaceBranch() {
            const parentMsg = this.sessionStore.state.currentMessages.find(m => m.id === this.msg.parentId);
            if (!parentMsg) {
                ElementPlus.ElMessage.error('未找到对应的用户消息');
                return;
            }
            const userText = this.ctx.actions.bodyText(parentMsg);
            if (!userText) {
                ElementPlus.ElMessage.error('用户消息内容为空');
                return;
            }
            this.sessionStore.replaceBranch(this.msg.id, userText);
        },

        /** 复制本条消息正文到剪贴板（只复制正文首块） */
        copyMessage() {
            this.ctx.actions.writeToClipboard(this.ctx.actions.bodyText(this.msg));
        },

        /** 重播本条消息的整轮音频 */
        playMessageAudio() {
            this.sessionStore.playMessageAudio(this.msg);
        },

        /** 进入助手消息编辑模式 */
        startAssistantEdit() {
            this.ctx.currentEditId = this.msg.id;
            this.ctx.editTargetRole = 'assistant';
            this.ctx.editDraft = this.ctx.actions.bodyText(this.msg);
            this.ctx.actions.focusEditTextarea();
        },

        /** 确认编辑助手消息：写回后端并就地更新正文首块 */
        async confirmAssistantEdit() {
            const draft = this.ctx.editDraft;
            if (!draft.trim()) {
                ElementPlus.ElMessage.error('消息内容不能为空');
                return;
            }
            try {
                const result = await API.message.editAssistant({ id: this.msg.id, content: draft });
                if (result.status === 200) {
                    const targetMsg = this.sessionStore.state.currentMessages.find(m => m.id === this.msg.id);
                    if (targetMsg && Array.isArray(targetMsg.contents)) {
                        // 只改正文首块：附件与追加的展示块原样保留，与后端 editAssistantMessage 同口径
                        const others = targetMsg.contents.slice(1);
                        targetMsg.contents.splice(0, targetMsg.contents.length,
                            { type: 'text', content: draft }, ...others);
                    }
                    Vue.nextTick(() => {
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
                this.ctx.actions.clearEditState();
            }
        },

        /** 取消助手消息编辑 */
        cancelAssistantEdit() {
            this.ctx.actions.clearEditState();
        },

        /**
         * 合并同一帧内的多次 mermaid 重渲染请求。
         * mermaid 异步渲染完成后调用，触发组件重渲染以把源码占位换成 SVG。
         */
        scheduleMermaidRefresh() {
            if (this._mermaidRefreshPending) return;
            this._mermaidRefreshPending = true;
            requestAnimationFrame(() => {
                this._mermaidRefreshPending = false;
                this.ctx.mermaidNonce++;
            });
        },

        /** 合并同一帧内的多次图标刷新（流式期间每次 chunk 都会触发组件更新） */
        scheduleIconRefresh() {
            if (this._iconRefreshPending) return;
            this._iconRefreshPending = true;
            requestAnimationFrame(() => {
                this._iconRefreshPending = false;
                if (typeof lucide !== 'undefined' && this.$el) {
                    lucide.createIcons({ root: this.$el });
                }
            });
        }
    },

    mounted: function () {
        // mermaid 异步渲染成功后重渲染，把流式中的源码占位替换成 SVG（合并到一帧）
        this._offMermaid = this.$md.onMermaidRendered(() => this.scheduleMermaidRefresh());
    },

    beforeUnmount: function () {
        if (this._offMermaid) {
            this._offMermaid();
            this._offMermaid = null;
        }
    },

    updated: function () {
        this.scheduleIconRefresh();
    }
};
