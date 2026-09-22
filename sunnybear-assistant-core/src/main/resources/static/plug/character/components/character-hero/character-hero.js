/**
 * 角色登场开场页（列表顶部插件，锚点 'message-list-top'）。
 *
 * 原为 character_index 消息区内联的「新对话开场」；重写后作为插件组件，
 * 通过 MessageAreaPlugins.registerSlot('message-list-top', ...) 挂载。
 *
 * 只在新对话（无会话、无消息）时显示。角色信息由页面启动时写入 CharacterPage。
 * 样式见 character-hero.css。
 *
 * Injects:
 *   sessionStore   — 会话/消息仓库（判断是否新对话）
 *   characterPage  — 页面级共享状态 { characterInfo }
 *
 * 依赖全局：lucide。
 */
const CharacterHero = {
    name: 'CharacterHero',

    template: `
    <div v-if="isNewChat"
         class="char-hero"
         :class="{ 'is-empty': !character }"
         :style="{'--main-color': mainColor}">
        <div class="char-hero-avatar">
            <img v-if="character && character.avatar"
                 :src="character.avatar"
                 :alt="character.name" />
            <i v-else :data-lucide="character ? greetingIcon : 'users'" class="char-hero-avatar-icon"></i>
        </div>
        <div class="char-hero-text">
            <div class="char-hero-name">{{ character ? character.name : '还没有选择角色' }}</div>
            <div v-if="!character" class="char-hero-line">去角色设置里挑一个角色，回来就能开始对话</div>
        </div>
    </div>`,

    inject: {
        sessionStore: { required: true },
        characterPage: { default: null }
    },

    computed: {
        mainColor: function () {
            return this.characterPage ? this.characterPage.mainColor : 'lightsalmon';
        },
        character: function () {
            return this.characterPage ? this.characterPage.characterInfo : null;
        },
        isNewChat: function () {
            return !this.sessionStore.currentSessionId && this.sessionStore.currentMessages.length === 0;
        },
        greetingIcon: function () {
            var hour = new Date().getHours();
            if (hour >= 6 && hour < 12) return 'sunrise';
            if (hour >= 12 && hour < 18) return 'sun';
            if (hour >= 18 && hour < 22) return 'sunset';
            return 'moon';
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined' && this.$el) lucide.createIcons({ root: this.$el });
    }
};
