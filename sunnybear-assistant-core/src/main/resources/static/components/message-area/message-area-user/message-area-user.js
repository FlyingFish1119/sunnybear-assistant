/**
 * 用户消息气泡组件。
 *
 * 负责用户消息气泡：正文/附件渲染、编辑态 textarea、编辑/复制/删除按钮。
 * 消息自身的动作（编辑 / 复制 / 删除）由本组件自持，直接调用 store / API；
 * 跨组件的共享状态（编辑态、主色、头像）仍走注入的共享上下文读取。
 *
 * Injects:
 *   sessionStore       — 会话/消息仓库（busy 状态、editMessage）
 *   messageAreaContext — 消息区共享上下文（编辑态 / 主色 / 通用工具动作）
 *
 * 依赖全局：API、ElementPlus、$md、$fileUrl、ColorUtils、auto-resize-textarea、
 *           message-area-tokens。
 */

const MessageAreaUser = {
    name: 'MessageAreaUser',

    inject: {
        sessionStore: { required: true },
        messageAreaContext: { required: true }
    },

    props: {
        msg: { type: Object, required: true }
    },

    template: `
    <div class="message-area-bubble"
         :style="{'background-color': userBubbleBg}">
        <!-- 编辑模式：显示 textarea -->
        <div v-if="ctx.currentEditId === msg.id">
            <auto-resize-textarea
                class="message-edit-textarea"
                :main-color="ctx.mainColor"
                v-model="ctx.editDraft"
                :max-height="300"
                :min-height="65"
                @submit="confirmEdit()"
                @cancel="cancelEdit()"
            ></auto-resize-textarea>
        </div>
        <!-- 正常模式：显示内容 -->
        <div v-else>
            <div v-for="(content, idx) in msg.contents" :key="idx" class="message-area-content-block">
                <div v-if="content.type === 'text'" class="markdown-body" v-html="renderContent(content)"></div>
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
        <!-- streaming 时空占位，防止高度抽搐 -->
        <div v-if="busy" class="message-area-bubble-actions" style="visibility: hidden;"></div>
        <!-- 编辑模式：确认/取消按钮，始终可见 -->
        <div v-else-if="ctx.currentEditId === msg.id" class="message-area-bubble-actions" style="opacity: 1;">
            <span class="branch-switch-arrow"
                  @click.stop="confirmEdit()"
                  title="确认编辑">
                <i style="width: 14px; height: 14px" data-lucide="check"></i>
            </span>
            <span class="branch-switch-arrow"
                  @click.stop="cancelEdit()"
                  title="取消编辑">
                <i style="width: 14px; height: 14px" data-lucide="x"></i>
            </span>
        </div>
        <!-- 真实按钮区 -->
        <div v-else class="message-area-bubble-actions">
            <span v-if="msg.siblingCount > 1" class="branch-switch-arrow"
                  :class="{ disabled: msg.siblingIndex === 0 }"
                  @click.stop="ctx.actions.switchBranch(msg, 'left')"
                  title="切换到上一个分支">
                <i style="width: 14px; height: 14px" data-lucide="chevron-left"></i>
            </span>
            <span v-if="msg.siblingCount > 1" class="branch-switch-counter">{{ msg.siblingIndex + 1 }} / {{ msg.siblingCount }}</span>
            <span v-if="msg.siblingCount > 1" class="branch-switch-arrow"
                  :class="{ disabled: msg.siblingIndex === msg.siblingCount - 1 }"
                  @click.stop="ctx.actions.switchBranch(msg, 'right')"
                  title="切换到下一个分支">
                <i style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
            </span>
            <span class="branch-switch-arrow"
                  @click.stop="startEdit()"
                  title="编辑消息">
                <i style="width: 14px; height: 14px" data-lucide="pencil"></i>
            </span>
            <span class="branch-switch-arrow"
                  @click.stop="copyMessage()"
                  title="复制消息">
                <i style="width: 14px; height: 14px" data-lucide="copy"></i>
            </span>
            <span class="branch-switch-arrow"
                  @click.stop="deleteUserMessage()"
                  title="删除消息">
                <i style="width: 14px; height: 14px" data-lucide="trash-2"></i>
            </span>
            <!-- 本轮 token 消耗（message-area-tokens 子组件） -->
            <message-area-tokens :msg="msg"></message-area-tokens>
        </div>
    </div>`,

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        /** 本轮不可交互（请求在途或流式输出中）：隐藏操作按钮 */
        busy: function () {
            return this.sessionStore.busy;
        },
        /** 用户消息气泡背景色：跟随主色自动变浅 */
        userBubbleBg: function () {
            return ColorUtils.lighten(this.ctx.mainColor, 0.25);
        }
    },

    methods: {
        /** 正文 Markdown（缓存到 content 对象的 text 槽：历史消息只解析一次） */
        renderContent(content) {
            return memoMsgHtml(content, 'text', content.content, this.$md.render);
        },

        /* ---------- 消息自身动作（编辑 / 复制 / 删除） ---------- */

        /** 进入编辑模式：写入共享编辑态并初始化草稿 */
        startEdit() {
            this.ctx.currentEditId = this.msg.id;
            this.ctx.editTargetRole = 'user';
            this.ctx.editDraft = this.ctx.actions.bodyText(this.msg);
            this.ctx.actions.focusEditTextarea();
        },

        /** 确认编辑：发送 edit 请求并清除编辑状态 */
        confirmEdit() {
            const draft = this.ctx.editDraft;
            if (!draft.trim()) {
                ElementPlus.ElMessage.error('消息内容不能为空');
                return;
            }
            this.sessionStore.editMessage(this.msg.id, draft);
            this.ctx.actions.clearEditState();
        },

        /** 取消编辑 */
        cancelEdit() {
            this.ctx.actions.clearEditState();
        },

        /** 删除本条用户消息及其所有子孙消息 */
        async deleteUserMessage() {
            try {
                const result = await API.message.deleteUser(this.msg.id);
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

        /** 复制本条消息正文到剪贴板（只复制正文首块） */
        copyMessage() {
            this.ctx.actions.writeToClipboard(this.ctx.actions.bodyText(this.msg));
        }
    },

    updated: function () {
        this.scheduleIconRefresh();
    },

    /** 合并同一帧内的多次图标刷新 */
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
};
