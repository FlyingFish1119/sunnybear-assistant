/**
 * 消息分组组件：一个 ReAct 组（assistant/tool 连续段）或一条用户消息。
 *
 * 负责组级别的导轨（react-rail：头像 + 主色竖线，仅组内第一条头像）与
 * 组头部（名称 + 时间，用户消息的头像画在名称旁）；组内每条消息的气泡
 * 交给 message-area-user / message-area-assistant / message-area-tool 渲染。
 *
 * Injects:
 *   sessionStore       — 会话/消息仓库
 *   messageAreaContext — 消息区共享上下文（编辑态/折叠态/头像兜底等）
 */

const MessageAreaGroup = {
    name: 'MessageAreaGroup',

    inject: {
        sessionStore: { required: true },
        messageAreaContext: { required: true }
    },

    props: {
        group: { type: Object, required: true }
    },

    template: `
    <div class="message-area-row"
         :class="[group.role, { 'is-active-group': isActiveGroup }]">
        <!-- 助手组左侧导轨：头像（仅组第一条）+ 主色淡竖线 -->
        <div v-if="group.role !== 'user'" class="react-rail">
            <img v-if="ctx.actions.getMessageAvatar(group.messages[0])"
                 :src="ctx.actions.getMessageAvatar(group.messages[0])"
                 class="message-avatar-top react-avatar"
                 :alt="group.messages[0].name + '头像'"
                 @error="ctx.assistantAvatarError = true">
            <div v-else class="message-avatar-top react-avatar message-avatar-default assistant-avatar-default">
                <span>{{ ctx.actions.getAvatarInitial(group.messages[0]) }}</span>
            </div>
            <span v-if="group.messages.length > 1" class="react-line"></span>
        </div>
        <div class="message-area-col">
            <!-- 名称 + 时间：仅组的第一条显示（用户消息头像在名称旁） -->
            <div class="message-header-row">
                <template v-if="group.role === 'user'">
                    <img v-if="ctx.actions.getMessageAvatar(group.messages[0])"
                         :src="ctx.actions.getMessageAvatar(group.messages[0])"
                         class="message-avatar-top"
                         :alt="group.messages[0].name + '头像'"
                         @error="ctx.userAvatarError = true">
                    <div v-else class="message-avatar-top message-avatar-default user-avatar-default">
                        <span>{{ ctx.actions.getAvatarInitial(group.messages[0]) }}</span>
                    </div>
                </template>
                <span class="message-header-meta">{{ group.messages[0].name }} · {{ group.messages[0].createTime }}</span>
            </div>
            <template v-for="msg in group.messages" :key="msg.id">
                <message-area-user v-if="msg.role === 'user'" :msg="msg"></message-area-user>
                <message-area-tool v-else-if="msg.role === 'tool'" :msg="msg"></message-area-tool>
                <message-area-assistant v-else :msg="msg"></message-area-assistant>
            </template>
        </div>
    </div>`,

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        /** 组内任一消息在流式 → 组处于活跃态（图标刷新 / 高亮范围用） */
        isActiveGroup: function () {
            for (const msg of this.group.messages) {
                if (this.ctx.actions.isStreamingMsg(msg)) return true;
            }
            return false;
        }
    }
};
