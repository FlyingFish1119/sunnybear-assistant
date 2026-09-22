/**
 * 世界观群聊开场页（列表顶部插件，锚点 'message-list-top'）。
 *
 * 只在新对话（无会话、无消息）时显示。世界没有头像，也没有默认问候语，
 * 开场只呈现一行「世界名」（未选世界时给出引导语）；样式见 world-greeting.css。
 *
 * Injects:
 *   sessionStore — 会话/消息仓库（判断是否新对话）
 *   worldPage    — 页面级共享状态
 */
const WorldGreeting = {
    name: 'WorldGreeting',

    template: `
    <div v-if="isNewChat" class="world-hero" :class="{ 'is-empty': !worldInfo }"
         :style="{'--main-color': mainColor}">
        <div class="world-hero-name">{{ worldInfo ? worldInfo.name : '还没有选择世界观' }}</div>
        <div v-if="!worldInfo" class="world-hero-line">去世界观设置里挑一个世界，回来就能开始群聊</div>
    </div>`,

    inject: {
        sessionStore: { required: true },
        worldPage: { required: true }
    },

    computed: {
        mainColor: function () { return this.worldPage.mainColor; },
        worldInfo: function () { return this.worldPage.worldInfo; },
        isNewChat: function () {
            return !this.sessionStore.currentSessionId && this.sessionStore.currentMessages.length === 0;
        }
    }
};
